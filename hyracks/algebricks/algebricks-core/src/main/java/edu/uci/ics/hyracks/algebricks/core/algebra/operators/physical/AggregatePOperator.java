/*
 * Copyright 2009-2013 by The Regents of the University of California
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * you may obtain a copy of the License from
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package edu.uci.ics.hyracks.algebricks.core.algebra.operators.physical;

import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.mutable.Mutable;

import edu.uci.ics.hyracks.algebricks.common.exceptions.AlgebricksException;
import edu.uci.ics.hyracks.algebricks.core.algebra.base.IHyracksJobBuilder;
import edu.uci.ics.hyracks.algebricks.core.algebra.base.ILogicalExpression;
import edu.uci.ics.hyracks.algebricks.core.algebra.base.ILogicalOperator;
import edu.uci.ics.hyracks.algebricks.core.algebra.base.IOptimizationContext;
import edu.uci.ics.hyracks.algebricks.core.algebra.base.LogicalVariable;
import edu.uci.ics.hyracks.algebricks.core.algebra.base.PhysicalOperatorTag;
import edu.uci.ics.hyracks.algebricks.core.algebra.expressions.AggregateFunctionCallExpression;
import edu.uci.ics.hyracks.algebricks.core.algebra.expressions.IExpressionRuntimeProvider;
import edu.uci.ics.hyracks.algebricks.core.algebra.operators.logical.AbstractLogicalOperator;
import edu.uci.ics.hyracks.algebricks.core.algebra.operators.logical.AggregateOperator;
import edu.uci.ics.hyracks.algebricks.core.algebra.operators.logical.IOperatorSchema;
import edu.uci.ics.hyracks.algebricks.core.algebra.properties.ILocalStructuralProperty;
import edu.uci.ics.hyracks.algebricks.core.algebra.properties.IPartitioningProperty;
import edu.uci.ics.hyracks.algebricks.core.algebra.properties.IPartitioningRequirementsCoordinator;
import edu.uci.ics.hyracks.algebricks.core.algebra.properties.IPhysicalPropertiesVector;
import edu.uci.ics.hyracks.algebricks.core.algebra.properties.PhysicalRequirements;
import edu.uci.ics.hyracks.algebricks.core.algebra.properties.StructuralPropertiesVector;
import edu.uci.ics.hyracks.algebricks.core.jobgen.impl.JobGenContext;
import edu.uci.ics.hyracks.algebricks.core.jobgen.impl.JobGenHelper;
import edu.uci.ics.hyracks.algebricks.runtime.base.IAggregateEvaluatorFactory;
import edu.uci.ics.hyracks.algebricks.runtime.operators.aggreg.AggregateRuntimeFactory;
import edu.uci.ics.hyracks.api.dataflow.value.RecordDescriptor;

public class AggregatePOperator extends AbstractPhysicalOperator {

    public AggregatePOperator() {
    }

    @Override
    public PhysicalOperatorTag getOperatorTag() {
        return PhysicalOperatorTag.AGGREGATE;
    }

    @Override
    public void computeDeliveredProperties(ILogicalOperator op, IOptimizationContext context) {
        AggregateOperator aggOp = (AggregateOperator) op;
        ILogicalOperator op2 = op.getInputs().get(0).getValue();
        if (aggOp.getExecutionMode() != AbstractLogicalOperator.ExecutionMode.UNPARTITIONED) {
            deliveredProperties = new StructuralPropertiesVector(op2.getDeliveredPhysicalProperties()
                    .getPartitioningProperty(), new ArrayList<ILocalStructuralProperty>());
        } else {
            deliveredProperties = new StructuralPropertiesVector(IPartitioningProperty.UNPARTITIONED,
                    new ArrayList<ILocalStructuralProperty>());
        }
    }

    @Override
    public PhysicalRequirements getRequiredPropertiesForChildren(ILogicalOperator op,
            IPhysicalPropertiesVector reqdByParent) {
        AggregateOperator aggOp = (AggregateOperator) op;
        StructuralPropertiesVector[] pv = new StructuralPropertiesVector[1];
        if (aggOp.isGlobal() && aggOp.getExecutionMode() == AbstractLogicalOperator.ExecutionMode.UNPARTITIONED) {
            pv[0] = new StructuralPropertiesVector(IPartitioningProperty.UNPARTITIONED, null);
            return new PhysicalRequirements(pv, IPartitioningRequirementsCoordinator.NO_COORDINATION);
        } else {
            return emptyUnaryRequirements();
        }

    }

    @Override
    public void contributeRuntimeOperator(IHyracksJobBuilder builder, JobGenContext context, ILogicalOperator op,
            IOperatorSchema opSchema, IOperatorSchema[] inputSchemas, IOperatorSchema outerPlanSchema)
            throws AlgebricksException {
        AggregateOperator aggOp = (AggregateOperator) op;
        List<LogicalVariable> variables = aggOp.getVariables();
        List<Mutable<ILogicalExpression>> expressions = aggOp.getExpressions();
        int[] outColumns = new int[variables.size()];
        for (int i = 0; i < outColumns.length; i++) {
            outColumns[i] = opSchema.findVariable(variables.get(i));
        }
        IAggregateEvaluatorFactory[] aggFactories = new IAggregateEvaluatorFactory[expressions.size()];
        IExpressionRuntimeProvider expressionRuntimeProvider = context.getExpressionRuntimeProvider();
        for (int i = 0; i < aggFactories.length; i++) {
            AggregateFunctionCallExpression aggFun = (AggregateFunctionCallExpression) expressions.get(i).getValue();
            aggFactories[i] = expressionRuntimeProvider.createAggregateFunctionFactory(aggFun,
                    context.getTypeEnvironment(op.getInputs().get(0).getValue()), inputSchemas, context);
        }

        AggregateRuntimeFactory runtime = new AggregateRuntimeFactory(aggFactories);

        // contribute one Asterix framewriter
        RecordDescriptor recDesc = JobGenHelper.mkRecordDescriptor(context.getTypeEnvironment(op), opSchema, context);
        builder.contributeMicroOperator(aggOp, runtime, recDesc);
        // and contribute one edge from its child
        ILogicalOperator src = aggOp.getInputs().get(0).getValue();
        builder.contributeGraphEdge(src, 0, aggOp, 0);
    }

    @Override
    public boolean isMicroOperator() {
        return true;
    }

    @Override
    public boolean expensiveThanMaterialization() {
        return true;
    }
}
