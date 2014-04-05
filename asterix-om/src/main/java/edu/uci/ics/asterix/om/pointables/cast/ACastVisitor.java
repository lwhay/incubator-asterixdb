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

package edu.uci.ics.asterix.om.pointables.cast;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import edu.uci.ics.asterix.common.exceptions.AsterixException;
import edu.uci.ics.asterix.om.pointables.AFlatValuePointable;
import edu.uci.ics.asterix.om.pointables.AListPointable;
import edu.uci.ics.asterix.om.pointables.ARecordPointable;
import edu.uci.ics.asterix.om.pointables.base.DefaultOpenFieldType;
import edu.uci.ics.asterix.om.pointables.base.IVisitablePointable;
import edu.uci.ics.asterix.om.pointables.visitor.IVisitablePointableVisitor;
import edu.uci.ics.asterix.om.types.ARecordType;
import edu.uci.ics.asterix.om.types.ATypeTag;
import edu.uci.ics.asterix.om.types.AbstractCollectionType;
import edu.uci.ics.asterix.om.types.EnumDeserializer;
import edu.uci.ics.asterix.om.types.IAType;
import edu.uci.ics.asterix.om.types.hierachy.ATypeHierarchy;
import edu.uci.ics.asterix.om.types.hierachy.ITypePromoteComputer;
import edu.uci.ics.hyracks.algebricks.common.utils.Triple;
import edu.uci.ics.hyracks.data.std.util.ArrayBackedValueStorage;

/**
 * This class is a IVisitablePointableVisitor implementation which recursively
 * visit a given record, list or flat value of a given type, and cast it to a
 * specified type. For example:
 * A record { "hobby": {{"music", "coding"}}, "id": "001", "name":
 * "Person Three"} which confirms to closed type ( id: string, name: string,
 * hobby: {{string}}? ) can be casted to a open type (id: string )
 * Since the open/closed part of a record has a completely different underlying
 * memory/storage layout, the visitor will change the layout as specified at
 * runtime.
 */
public class ACastVisitor implements IVisitablePointableVisitor<Void, Triple<IVisitablePointable, IAType, Boolean>> {

    private final Map<IVisitablePointable, ARecordCaster> raccessorToCaster = new HashMap<IVisitablePointable, ARecordCaster>();
    private final Map<IVisitablePointable, AListCaster> laccessorToCaster = new HashMap<IVisitablePointable, AListCaster>();

    @Override
    public Void visit(AListPointable accessor, Triple<IVisitablePointable, IAType, Boolean> arg)
            throws AsterixException {
        AListCaster caster = laccessorToCaster.get(accessor);
        if (caster == null) {
            caster = new AListCaster();
            laccessorToCaster.put(accessor, caster);
        }
        try {
            if (arg.second.getTypeTag().equals(ATypeTag.ANY)) {
                arg.second = DefaultOpenFieldType.NESTED_OPEN_AUNORDERED_LIST_TYPE;
            }
            caster.castList(accessor, arg.first, (AbstractCollectionType) arg.second, this);
        } catch (Exception e) {
            throw new AsterixException(e);
        }
        return null;
    }

    @Override
    public Void visit(ARecordPointable accessor, Triple<IVisitablePointable, IAType, Boolean> arg)
            throws AsterixException {
        ARecordCaster caster = raccessorToCaster.get(accessor);
        if (caster == null) {
            caster = new ARecordCaster();
            raccessorToCaster.put(accessor, caster);
        }
        try {
            if (arg.second.getTypeTag().equals(ATypeTag.ANY)) {
                arg.second = DefaultOpenFieldType.NESTED_OPEN_RECORD_TYPE;
            }
            caster.castRecord(accessor, arg.first, (ARecordType) arg.second, this);
        } catch (Exception e) {
            throw new AsterixException(e);
        }
        return null;
    }

    @Override
    public Void visit(AFlatValuePointable accessor, Triple<IVisitablePointable, IAType, Boolean> arg)
            throws AsterixException {
        if (arg.second == null) {
            // for open type case
            arg.first.set(accessor);
            return null;
        }
        // set the pointer for result
        ATypeTag reqTypeTag = ((IAType) (arg.second)).getTypeTag();
        ATypeTag inputTypeTag = EnumDeserializer.ATYPETAGDESERIALIZER.deserialize(accessor.getByteArray()[accessor
                .getStartOffset()]);
        if (!needPromote(inputTypeTag, reqTypeTag)) {
            arg.first.set(accessor);
        } else {
            ArrayBackedValueStorage castBuffer = new ArrayBackedValueStorage();
            ITypePromoteComputer promoteComputer = ATypeHierarchy.getTypePromoteComputer(inputTypeTag, reqTypeTag);
            if (promoteComputer != null) {

                try {
                    // do the promotion; note that the type tag field should be skipped
                    promoteComputer.promote(accessor.getByteArray(), accessor.getStartOffset() + 1,
                            accessor.getLength() - 1, castBuffer.getDataOutput());
                    arg.first.set(castBuffer);
                } catch (IOException e) {
                    throw new AsterixException(e);
                }
            } else {
                throw new AsterixException("Type mismatch: cannot cast type " + inputTypeTag + " to " + reqTypeTag);
            }
        }

        return null;
    }

    private boolean needPromote(ATypeTag tag0, ATypeTag tag1) {
        if (tag0 == tag1) {
            return false;
        }
        if (tag0 == ATypeTag.NULL) {
            return false;
        }
        return true;
    }

}
