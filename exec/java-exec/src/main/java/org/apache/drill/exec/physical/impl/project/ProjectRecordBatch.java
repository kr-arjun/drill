/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.drill.exec.physical.impl.project;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;

import org.apache.drill.common.expression.ErrorCollector;
import org.apache.drill.common.expression.ErrorCollectorImpl;
import org.apache.drill.common.expression.FieldReference;
import org.apache.drill.common.expression.LogicalExpression;
import org.apache.drill.common.expression.PathSegment;
import org.apache.drill.common.expression.PathSegment.NameSegment;
import org.apache.drill.common.expression.SchemaPath;
import org.apache.drill.common.logical.data.NamedExpression;
import org.apache.drill.exec.exception.ClassTransformationException;
import org.apache.drill.exec.exception.SchemaChangeException;
import org.apache.drill.exec.expr.ClassGenerator;
import org.apache.drill.exec.expr.ClassGenerator.HoldingContainer;
import org.apache.drill.exec.expr.CodeGenerator;
import org.apache.drill.exec.expr.ExpressionTreeMaterializer;
import org.apache.drill.exec.expr.TypeHelper;
import org.apache.drill.exec.expr.ValueVectorReadExpression;
import org.apache.drill.exec.expr.ValueVectorWriteExpression;
import org.apache.drill.exec.memory.OutOfMemoryException;
import org.apache.drill.exec.ops.FragmentContext;
import org.apache.drill.exec.physical.config.Project;
import org.apache.drill.exec.record.AbstractSingleRecordBatch;
import org.apache.drill.exec.record.BatchSchema.SelectionVectorMode;
import org.apache.drill.exec.record.MaterializedField;
import org.apache.drill.exec.record.RecordBatch;
import org.apache.drill.exec.record.TransferPair;
import org.apache.drill.exec.record.TypedFieldId;
import org.apache.drill.exec.record.VectorWrapper;
import org.apache.drill.exec.vector.AllocationHelper;
import org.apache.drill.exec.vector.ValueVector;

import com.carrotsearch.hppc.IntOpenHashSet;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.sun.codemodel.JExpr;

public class ProjectRecordBatch extends AbstractSingleRecordBatch<Project>{
  static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(ProjectRecordBatch.class);

  private Projector projector;
  private List<ValueVector> allocationVectors;
  private boolean hasRemainder = false;
  private int remainderIndex = 0;
  private int recordCount;

  public ProjectRecordBatch(Project pop, RecordBatch incoming, FragmentContext context) throws OutOfMemoryException {
    super(pop, context, incoming);
  }

  @Override
  public int getRecordCount() {
    return recordCount;
  }

  @Override
  public IterOutcome next() {
    if (hasRemainder) {
      handleRemainder();
      return IterOutcome.OK;
    }
    return super.next();
  }

  @Override
  protected void doWork() {
//    VectorUtil.showVectorAccessibleContent(incoming, ",");
    int incomingRecordCount = incoming.getRecordCount();
    for(ValueVector v : this.allocationVectors){
      AllocationHelper.allocate(v, incomingRecordCount, 250);
//      v.allocateNew();
    }
    int outputRecords = projector.projectRecords(0, incomingRecordCount, 0);
    if (outputRecords < incomingRecordCount) {
      for(ValueVector v : allocationVectors){
        ValueVector.Mutator m = v.getMutator();
        m.setValueCount(outputRecords);
      }
      hasRemainder = true;
      remainderIndex = outputRecords;
      this.recordCount = remainderIndex;
    } else {
      for(ValueVector v : allocationVectors){
        ValueVector.Mutator m = v.getMutator();
        m.setValueCount(incomingRecordCount);
      }
      for(VectorWrapper<?> v: incoming) {
        v.clear();
      }
      this.recordCount = outputRecords;
    }
  }

  private void handleRemainder() {
    int remainingRecordCount = incoming.getRecordCount() - remainderIndex;
    for(ValueVector v : this.allocationVectors){
      AllocationHelper.allocate(v, remainingRecordCount, 250);
    }
    int outputIndex = projector.projectRecords(remainderIndex, remainingRecordCount, 0);
    if (outputIndex < incoming.getRecordCount()) {
      for(ValueVector v : allocationVectors){
        ValueVector.Mutator m = v.getMutator();
        m.setValueCount(outputIndex - remainderIndex);
      }
      hasRemainder = true;
      this.recordCount = outputIndex - remainderIndex;
      remainderIndex = outputIndex;
    } else {
      for(ValueVector v : allocationVectors){
        ValueVector.Mutator m = v.getMutator();
        m.setValueCount(remainingRecordCount);
      }
      hasRemainder = false;
      remainderIndex = 0;
      for(VectorWrapper<?> v: incoming) {
        v.clear();
      }
      this.recordCount = remainingRecordCount;
    }
  }

  /** hack to make ref and full work together... need to figure out if this is still necessary. **/
  private FieldReference getRef(NamedExpression e){
    FieldReference ref = e.getRef();
    PathSegment seg = ref.getRootSegment();

//    if(seg.isNamed() && "output".contentEquals(seg.getNameSegment().getPath())){
//      return new FieldReference(ref.getPath().toString().subSequence(7, ref.getPath().length()), ref.getPosition());
//    }
    return ref;
  }

  private boolean isAnyWildcard(List<NamedExpression> exprs){
    for(NamedExpression e : exprs){
      if(isWildcard(e)) return true;
    }
    return false;
  }

  private boolean isWildcard(NamedExpression ex){
    if( !(ex.getExpr() instanceof SchemaPath)) return false;
    NameSegment expr = ((SchemaPath)ex.getExpr()).getRootSegment();
    NameSegment ref = ex.getRef().getRootSegment();
    return ref.getPath().equals("*") && expr.getPath().equals("*");
  }

  @Override
  protected void setupNewSchema() throws SchemaChangeException{
    this.allocationVectors = Lists.newArrayList();
    container.clear();
    final List<NamedExpression> exprs = popConfig.getExprs();
    final ErrorCollector collector = new ErrorCollectorImpl();
    final List<TransferPair> transfers = Lists.newArrayList();

    final ClassGenerator<Projector> cg = CodeGenerator.getRoot(Projector.TEMPLATE_DEFINITION, context.getFunctionRegistry());

    IntOpenHashSet transferFieldIds = new IntOpenHashSet();

    boolean isAnyWildcard = isAnyWildcard(exprs);

    if(isAnyWildcard){
      for(VectorWrapper<?> wrapper : incoming){
        ValueVector vvIn = wrapper.getValueVector();

        String name = vvIn.getField().getPath().getLastSegment().getNameSegment().getPath();
        FieldReference ref = new FieldReference(name);
        TransferPair tp = wrapper.getValueVector().getTransferPair(ref);
        transfers.add(tp);
        container.add(tp.getTo());
      }
    }else{
      for(int i = 0; i < exprs.size(); i++){
        final NamedExpression namedExpression = exprs.get(i);
        final LogicalExpression expr = ExpressionTreeMaterializer.materialize(namedExpression.getExpr(), incoming, collector, context.getFunctionRegistry());
        final MaterializedField outputField = MaterializedField.create(getRef(namedExpression), expr.getMajorType());
        if(collector.hasErrors()){
          throw new SchemaChangeException(String.format("Failure while trying to materialize incoming schema.  Errors:\n %s.", collector.toErrorString()));
        }


        // add value vector to transfer if direct reference and this is allowed, otherwise, add to evaluation stack.
        if(expr instanceof ValueVectorReadExpression && incoming.getSchema().getSelectionVectorMode() == SelectionVectorMode.NONE
                && !((ValueVectorReadExpression) expr).hasReadPath()
                && !isAnyWildcard
                && !transferFieldIds.contains(((ValueVectorReadExpression) expr).getFieldId().getFieldIds()[0])
                && !((ValueVectorReadExpression) expr).hasReadPath()) {
          ValueVectorReadExpression vectorRead = (ValueVectorReadExpression) expr;
          TypedFieldId id = vectorRead.getFieldId();
          ValueVector vvIn = incoming.getValueAccessorById(id.getIntermediateClass(), id.getFieldIds()).getValueVector();
          Preconditions.checkNotNull(incoming);

          TransferPair tp = vvIn.getTransferPair(getRef(namedExpression));
          transfers.add(tp);
          container.add(tp.getTo());
          transferFieldIds.add(vectorRead.getFieldId().getFieldIds()[0]);
//          logger.debug("Added transfer.");
        }else{
          // need to do evaluation.
          ValueVector vector = TypeHelper.getNewVector(outputField, oContext.getAllocator());
          allocationVectors.add(vector);
          TypedFieldId fid = container.add(vector);
          ValueVectorWriteExpression write = new ValueVectorWriteExpression(fid, expr, true);
          HoldingContainer hc = cg.addExpr(write);


          cg.getEvalBlock()._if(hc.getValue().eq(JExpr.lit(0)))._then()._return(JExpr.FALSE);
          logger.debug("Added eval.");
        }
      }


    }
    cg.rotateBlock();
    cg.getEvalBlock()._return(JExpr.TRUE);

    container.buildSchema(SelectionVectorMode.NONE);

    try {
      this.projector = context.getImplementationClass(cg.getCodeGenerator());
      projector.setup(context, incoming, this, transfers);
    } catch (ClassTransformationException | IOException e) {
      throw new SchemaChangeException("Failure while attempting to load generated class", e);
    }
  }


}
