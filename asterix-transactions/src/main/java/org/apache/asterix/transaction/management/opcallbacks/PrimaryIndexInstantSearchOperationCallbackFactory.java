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

package org.apache.asterix.transaction.management.opcallbacks;

import org.apache.asterix.common.context.ITransactionSubsystemProvider;
import org.apache.asterix.common.exceptions.ACIDException;
import org.apache.asterix.common.transactions.AbstractOperationCallbackFactory;
import org.apache.asterix.common.transactions.ITransactionContext;
import org.apache.asterix.common.transactions.ITransactionSubsystem;
import org.apache.asterix.common.transactions.JobId;
import org.apache.hyracks.api.context.IHyracksTaskContext;
import org.apache.hyracks.api.exceptions.HyracksDataException;
import org.apache.hyracks.storage.am.common.api.ISearchOperationCallback;
import org.apache.hyracks.storage.am.common.api.ISearchOperationCallbackFactory;

public class PrimaryIndexInstantSearchOperationCallbackFactory extends AbstractOperationCallbackFactory implements
        ISearchOperationCallbackFactory {

    private static final long serialVersionUID = 1L;

    public PrimaryIndexInstantSearchOperationCallbackFactory(JobId jobId, int datasetId, int[] entityIdFields,
            ITransactionSubsystemProvider txnSubsystemProvider, byte resourceType) {
        super(jobId, datasetId, entityIdFields, txnSubsystemProvider, resourceType);
    }

    @Override
    public ISearchOperationCallback createSearchOperationCallback(long resourceId, IHyracksTaskContext ctx)
            throws HyracksDataException {
        ITransactionSubsystem txnSubsystem = txnSubsystemProvider.getTransactionSubsystem(ctx);
        try {
            ITransactionContext txnCtx = txnSubsystem.getTransactionManager().getTransactionContext(jobId, false);
            return new PrimaryIndexInstantSearchOperationCallback(datasetId, primaryKeyFields,
                    txnSubsystem.getLockManager(), txnCtx);
        } catch (ACIDException e) {
            throw new HyracksDataException(e);
        }
    }

}
