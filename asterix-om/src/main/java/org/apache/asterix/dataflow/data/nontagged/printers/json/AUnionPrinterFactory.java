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
package org.apache.asterix.dataflow.data.nontagged.printers.json;

import java.io.PrintStream;
import java.util.List;

import org.apache.asterix.formats.nontagged.AqlJSONPrinterFactoryProvider;
import org.apache.asterix.om.types.ATypeTag;
import org.apache.asterix.om.types.AUnionType;
import org.apache.asterix.om.types.IAType;
import org.apache.hyracks.algebricks.common.exceptions.AlgebricksException;
import org.apache.hyracks.algebricks.data.IPrinter;
import org.apache.hyracks.algebricks.data.IPrinterFactory;

public class AUnionPrinterFactory implements IPrinterFactory {

    private static final long serialVersionUID = 1L;

    private AUnionType unionType;

    public AUnionPrinterFactory(AUnionType unionType) {
        this.unionType = unionType;
    }

    @Override
    public IPrinter createPrinter() {

        return new IPrinter() {

            private IPrinter[] printers;
            private List<IAType> unionList;

            @Override
            public void init() throws AlgebricksException {
                unionList = unionType.getUnionList();
                printers = new IPrinter[unionType.getUnionList().size()];
                for (int i = 0; i < printers.length; i++) {
                    printers[i] = (AqlJSONPrinterFactoryProvider.INSTANCE.getPrinterFactory(unionType.getUnionList()
                            .get(i))).createPrinter();
                    printers[i].init();
                }
            }

            @Override
            public void print(byte[] b, int s, int l, PrintStream ps) throws AlgebricksException {
                ATypeTag tag = unionList.get(b[s + 1]).getTypeTag();
                if (tag == ATypeTag.UNION)
                    printers[b[s + 1]].print(b, s + 1, l, ps);
                else {
                    if (tag == ATypeTag.ANY)
                        printers[b[s + 1]].print(b, s + 2, l, ps);
                    else
                        printers[b[s + 1]].print(b, s + 1, l, ps);
                }
            }
        };
    }

}
