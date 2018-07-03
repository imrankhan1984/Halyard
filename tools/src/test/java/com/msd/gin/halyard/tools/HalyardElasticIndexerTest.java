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
package com.msd.gin.halyard.tools;

import com.msd.gin.halyard.common.HBaseServerTestInstance;
import com.msd.gin.halyard.common.HalyardTableUtils;
import com.msd.gin.halyard.sail.HBaseSail;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import org.apache.avro.data.Json;
import org.apache.commons.codec.binary.Hex;
import org.apache.hadoop.util.ToolRunner;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.json.JSONObject;
import static org.junit.Assert.*;
import org.junit.Test;

/**
 *
 * @author Adam Sotona (MSD)
 */
public class HalyardElasticIndexerTest {

    @Test
    public void testElasticIndexer() throws Exception {
        HBaseSail sail = new HBaseSail(HBaseServerTestInstance.getInstanceConfig(), "elasticTable", true, 0, true, 0, null, null);
        sail.initialize();
        ValueFactory vf = SimpleValueFactory.getInstance();
        for (int i = 0; i < 100; i++) {
            sail.addStatement(vf.createIRI("http://whatever/NTsubj"), vf.createIRI("http://whatever/NTpred" + i),  vf.createLiteral("whatever NT value " + i));
        }
        sail.commit();
        sail.close();

        final ArrayList<String> response = new ArrayList<>(200);

        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/my_index", new HttpHandler() {
            @Override
            public void handle(HttpExchange he) throws IOException {
                if ("POST".equalsIgnoreCase(he.getRequestMethod())) {
                    try (BufferedReader br = new BufferedReader(new InputStreamReader(he.getRequestBody(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = br.readLine()) != null) {
                            response.add(line);
                        }
                    }
                }
                he.sendResponseHeaders(200, 0);
            }
        });
        server.start();
        try {
            assertEquals(0, ToolRunner.run(HBaseServerTestInstance.getInstanceConfig(), new HalyardElasticIndexer(),
                new String[]{"-s", "elasticTable", "-t", "http://localhost:" + server.getAddress().getPort() + "/my_index"}));
        } finally {
            server.stop(0);
        }

        assertEquals(200, response.size());
        for (int i=0; i<200; i+=2) {
            String hash = new JSONObject(response.get(i)).getJSONObject("index").getString("_id");
            String literal = "\"" + new JSONObject(response.get(i+1)).getString("l") + "\"";
            assertEquals("Invalid hash for literal " + literal, Hex.encodeHexString(HalyardTableUtils.hashKey(literal.getBytes(StandardCharsets.UTF_8))), hash);
        }
    }

    @Test
    public void testRunNoArgs() throws Exception {
        assertEquals(-1, new HalyardElasticIndexer().run(new String[0]));
    }
}