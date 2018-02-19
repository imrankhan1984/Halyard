/*
 * Copyright 2018 Merck Sharp & Dohme Corp. a subsidiary of Merck & Co.,
 * Inc., Kenilworth, NJ, USA.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.msd.gin.halyard.rio;

import static com.msd.gin.halyard.rio.JSONParserTest.assertEquals;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.RDFParser;
import org.eclipse.rdf4j.rio.Rio;
import org.eclipse.rdf4j.rio.helpers.ContextStatementCollector;
import static org.junit.Assert.assertNotNull;
import org.junit.Test;

/**
 *
 * @author Adam Sotona (MSD)
 */
public class NDJSONLDParserTest {

    @Test
    public void testJSONParserFactory() {
        assertNotNull(Rio.createParser(NDJSONLDParser.NDJSONLD));
    }

    @Test
    public void testJSONParser() throws Exception {
        Model transformedModel = new LinkedHashModel();
        RDFParser parser = new JSONParser();
        parser.getParserConfig().set(JSONParser.GENERATE_ONTOLOGY, true);
        parser.setRDFHandler(new ContextStatementCollector(transformedModel, SimpleValueFactory.getInstance()));
        parser.parse(NDJSONLDParserTest.class.getResourceAsStream("efo_test.ndjsonld"), "http://test/");

        Model expectedModel = Rio.parse(JSONParser.class.getResourceAsStream("efo_test.ttl"), "http://test/", RDFFormat.TURTLE);

        assertEquals(expectedModel, transformedModel);
    }
}
