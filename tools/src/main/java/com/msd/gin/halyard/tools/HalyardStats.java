/*
 * Copyright 2016 Merck Sharp & Dohme Corp. a subsidiary of Merck & Co.,
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

import com.msd.gin.halyard.common.HalyardTableUtils;
import com.msd.gin.halyard.tools.HalyardExport.ExportException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Optional;
import java.util.Properties;
import java.util.logging.Logger;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.PosixParser;
import org.apache.commons.compress.compressors.CompressorException;
import org.apache.commons.compress.compressors.CompressorStreamFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.client.HTable;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.io.ImmutableBytesWritable;
import org.apache.hadoop.hbase.mapreduce.TableMapReduceUtil;
import org.apache.hadoop.hbase.mapreduce.TableMapper;
import org.apache.hadoop.hbase.protobuf.generated.AuthenticationProtos;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.MRJobConfig;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.hadoop.mapreduce.lib.output.NullOutputFormat;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;
import org.apache.htrace.Trace;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.RDFParser;
import org.eclipse.rdf4j.rio.RDFWriter;
import org.eclipse.rdf4j.rio.Rio;
import org.eclipse.rdf4j.rio.helpers.AbstractRDFHandler;
import org.eclipse.rdf4j.rio.ntriples.NTriplesUtil;

/**
 * Map-only MapReduce tool providing parallel SPARQL export functionality
 * @author Adam Sotona (MSD)
 */
public class HalyardStats implements Tool {

    private static final String SOURCE = "halyard.stats.source";
    private static final String TARGET = "halyard.stats.target";

    private static final Logger LOG = Logger.getLogger(HalyardStats.class.getName());
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final byte[] TYPE_HASH = HalyardTableUtils.hashKey(NTriplesUtil.toNTriplesString(RDF.TYPE).getBytes(UTF8));


    private Configuration conf;

    static String getRoot(Configuration cfg) {
        String hbaseRoot = cfg.getTrimmed("hbase.rootdir");
        if (!hbaseRoot.endsWith("/")) hbaseRoot = hbaseRoot + "/";
        return hbaseRoot + cfg.getTrimmed(SOURCE).replace(':', '/');
    }

    static class StatsMapper extends TableMapper<Text, LongWritable>  {

        final SimpleValueFactory ssf = SimpleValueFactory.getInstance();

        class GraphCounter {
            final String graph;
            long triples, subjects, predicates, objects, classes;

            public GraphCounter(String graph) {
                this.graph = graph;
            }

            private void _report(Context output, String key, long value) throws IOException, InterruptedException {
                if (value > 0) {
                    output.write(new Text(key), new LongWritable(value));
                }
            }

            public void report(Context output) throws IOException, InterruptedException {
                _report(output, graph + ":triples", triples);
                _report(output, graph + ":distinctSubjects", subjects);
                _report(output, graph + ":properties", predicates);
                _report(output, graph + ":distinctObjects", objects);
                _report(output, graph + ":classes", classes);
            }
        }

        final byte[] lastKeyFragment = new byte[20], lastCtxFragment = new byte[20], lastClassFragment = new byte[20];
        GraphCounter root, ctxGraph;
        byte lastRegion = -1;

        @Override
        protected void setup(Context context) throws IOException, InterruptedException {
            this.root = new GraphCounter(getRoot(context.getConfiguration()));
        }

        private boolean matchAndCopyKey(byte[] source, int offset, byte[] target) {
            boolean match = true;
            for (int i=0; i<20; i++) {
                byte b = source[i + offset];
                if (b != target[i]) {
                    target[i] = b;
                    match = false;
                }
            }
            return match;
        }

        @Override
        protected void map(ImmutableBytesWritable key, Result value, Context output) throws IOException, InterruptedException {
            byte region = key.get()[key.getOffset()];
            if (region < 3) {
                if (!matchAndCopyKey(key.get(), key.getOffset() + 1, lastKeyFragment) || region != lastRegion) {
                    switch (region) {
                        case HalyardTableUtils.SPO_PREFIX:
                            root.subjects++;
                            break;
                        case HalyardTableUtils.POS_PREFIX:
                            root.predicates++;
                            break;
                        case HalyardTableUtils.OSP_PREFIX:
                            root.objects++;
                            break;
                    }
                }
                if (region == HalyardTableUtils.SPO_PREFIX) {
                    root.triples += value.rawCells().length;
                } else if (region == HalyardTableUtils.POS_PREFIX
                        && Arrays.equals(TYPE_HASH, lastKeyFragment)
                        && (!matchAndCopyKey(key.get(), key.getOffset() + 21, lastClassFragment) || region != lastRegion)) {
                    root.classes++;
                }
            } else {
                if (!matchAndCopyKey(key.get(), key.getOffset() + 1, lastCtxFragment) || region != lastRegion) {
                    if (ctxGraph != null) {
                        ctxGraph.report(output);
                    }
                    Cell c[] = value.rawCells();
                    ByteBuffer bb = ByteBuffer.wrap(c[0].getQualifierArray(), c[0].getQualifierOffset(), c[0].getQualifierLength());
                    int skip = bb.getInt() + bb.getInt() + bb.getInt();
                    bb.position(bb.position() + skip);
                    byte[] cb = new byte[bb.remaining()];
                    bb.get(cb);
                    ctxGraph = new GraphCounter(NTriplesUtil.parseURI(new String(cb,UTF8), ssf).stringValue());
                }
                if (!matchAndCopyKey(key.get(), key.getOffset() + 21, lastKeyFragment) || region != lastRegion) {
                    switch (region) {
                        case HalyardTableUtils.CSPO_PREFIX:
                            ctxGraph.subjects++;
                            break;
                        case HalyardTableUtils.CPOS_PREFIX:
                            ctxGraph.predicates++;
                            break;
                        case HalyardTableUtils.COSP_PREFIX:
                            ctxGraph.objects++;
                            break;
                    }
                }
                if (region == HalyardTableUtils.CSPO_PREFIX) {
                    ctxGraph.triples += value.rawCells().length;
                } else if (region == HalyardTableUtils.CPOS_PREFIX
                    && Arrays.equals(TYPE_HASH, lastKeyFragment)
                    && (!matchAndCopyKey(key.get(), key.getOffset() + 41, lastClassFragment) || region != lastRegion)) {
                        ctxGraph.classes++;
                }
            }
            lastRegion = region;
            output.progress();
        }

        @Override
        protected void cleanup(Context output) throws IOException, InterruptedException {
            root.report(output);
            if (ctxGraph != null) ctxGraph.report(output);
        }

    }

    static final String VOID_PREFIX = "http://rdfs.org/ns/void#";
    static final String SD_PREFIX = "http://www.w3.org/ns/sparql-service-description#";

    static class StatsReducer extends Reducer<Text, LongWritable, NullWritable, NullWritable>  {

        final SimpleValueFactory ssf = SimpleValueFactory.getInstance();
        OutputStream out;
        RDFWriter writer;

        @Override
        protected void setup(Context context) throws IOException, InterruptedException {
            Configuration conf = context.getConfiguration();
            String targetUrl = conf.get(TARGET);
            out = FileSystem.get(URI.create(targetUrl), conf).create(new Path(targetUrl));
            try {
                if (targetUrl.endsWith(".bz2")) {
                    out = new CompressorStreamFactory().createCompressorOutputStream(CompressorStreamFactory.BZIP2, out);
                    targetUrl = targetUrl.substring(0, targetUrl.length() - 4);
                } else if (targetUrl.endsWith(".gz")) {
                    out = new CompressorStreamFactory().createCompressorOutputStream(CompressorStreamFactory.GZIP, out);
                    targetUrl = targetUrl.substring(0, targetUrl.length() - 3);
                }
            } catch (CompressorException ce) {
                throw new IOException(ce);
            }
            Optional<RDFFormat> form = Rio.getWriterFormatForFileName(targetUrl);
            if (!form.isPresent()) throw new IOException("Unsupported target file format extension: " + targetUrl);
            writer = Rio.createWriter(form.get(), out);
            String root = getRoot(conf);
            writer.handleNamespace("", root.substring(0, root.lastIndexOf('/') + 1));
            writer.handleNamespace("sd", SD_PREFIX);
            writer.handleNamespace("void", VOID_PREFIX);
            writer.startRDF();
        }

        @Override
	public void reduce(Text key, Iterable<LongWritable> values, Context context) throws IOException, InterruptedException {
            int i = 0;
            for (LongWritable val : values) {
                    i += val.get();
            }
            String kp = key.toString();
            int split = kp.lastIndexOf(':');
            writer.handleStatement(
                ssf.createStatement(
                    ssf.createIRI(kp.substring(0, split)),
                    ssf.createIRI(VOID_PREFIX, kp.substring(split+1)),
                    ssf.createLiteral(i)));
	}

        @Override
        protected void cleanup(Context context) throws IOException, InterruptedException {
            writer.endRDF();
            out.close();
        }
    }
    private static Option newOption(String opt, String argName, String description) {
        Option o = new Option(opt, null, argName != null, description);
        o.setArgName(argName);
        return o;
    }

    private static void printHelp(Options options) {
        new HelpFormatter().printHelp(100, "stats", "...", options, "Example: stats [-D" + MRJobConfig.QUEUE_NAME + "=proofofconcepts] -s my_dataset -t hdfs:/my_folder/my_stats.ttl", true);
    }

    @Override
    public int run(String[] args) throws Exception {
        Options options = new Options();
        options.addOption(newOption("h", null, "Prints this help"));
        options.addOption(newOption("v", null, "Prints version"));
        options.addOption(newOption("s", "source_htable", "Source HBase table with Halyard RDF store"));
        options.addOption(newOption("t", "target_url", "hdfs://<path>/<file_name>.<ext>"));
        try {
            CommandLine cmd = new PosixParser().parse(options, args);
            if (args.length == 0 || cmd.hasOption('h')) {
                printHelp(options);
                return -1;
            }
            if (cmd.hasOption('v')) {
                Properties p = new Properties();
                try (InputStream in = HalyardExport.class.getResourceAsStream("/META-INF/maven/com.msd.gin.halyard/hbasesail/pom.properties")) {
                    if (in != null) p.load(in);
                }
                System.out.println("Halyard Stats version " + p.getProperty("version", "unknown"));
                return 0;
            }
            if (!cmd.getArgList().isEmpty()) throw new ExportException("Unknown arguments: " + cmd.getArgList().toString());
            for (char c : "st".toCharArray()) {
                if (!cmd.hasOption(c))  throw new ExportException("Missing mandatory option: " + c);
            }
            for (char c : "st".toCharArray()) {
                String s[] = cmd.getOptionValues(c);
                if (s != null && s.length > 1)  throw new ExportException("Multiple values for option: " + c);
            }
            String source = cmd.getOptionValue('s');
            String target = cmd.getOptionValue('t');
            getConf().set(SOURCE, source);
            getConf().set(TARGET, target);
            TableMapReduceUtil.addDependencyJars(getConf(),
                   HalyardExport.class,
                   NTriplesUtil.class,
                   Rio.class,
                   AbstractRDFHandler.class,
                   RDFFormat.class,
                   RDFParser.class,
                   HTable.class,
                   HBaseConfiguration.class,
                   AuthenticationProtos.class,
                   Trace.class);
            HBaseConfiguration.addHbaseResources(getConf());
            Job job = Job.getInstance(getConf(), "HalyardStats " + source + " -> " + target);
            job.setJarByClass(HalyardStats.class);
            TableMapReduceUtil.initCredentials(job);

            Scan scan = new Scan();
            scan.addFamily("e".getBytes(UTF8));
            scan.setMaxVersions(1);

            TableMapReduceUtil.initTableMapperJob(
                    source,
                    scan,
                    StatsMapper.class,
                    Text.class,
                    LongWritable.class,
                    job);
            job.setReducerClass(StatsReducer.class);
            job.setNumReduceTasks(1);
            job.setOutputFormatClass(NullOutputFormat.class);
            if (job.waitForCompletion(true)) {
                LOG.info("Stats Generation Completed..");
                return 0;
            }
            return -1;
        } catch (RuntimeException exp) {
            System.out.println(exp.getMessage());
            printHelp(options);
            throw exp;
        }

    }

    @Override
    public Configuration getConf() {
        return this.conf;
    }

    @Override
    public void setConf(final Configuration c) {
        this.conf = c;
    }

    /**
     * Main of the HalyardStats
     * @param args String command line arguments
     * @throws Exception throws Exception in case of any problem
     */
    public static void main(String[] args) throws Exception {
        System.exit(ToolRunner.run(new Configuration(), new HalyardStats(), args));
    }
}
