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
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import org.apache.commons.cli.CommandLine;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.Seekable;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.client.HTable;
import org.apache.hadoop.hbase.io.ImmutableBytesWritable;
import org.apache.hadoop.hbase.mapreduce.HFileOutputFormat2;
import org.apache.hadoop.hbase.mapreduce.LoadIncrementalHFiles;
import org.apache.hadoop.hbase.mapreduce.TableMapReduceUtil;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.compress.CodecPool;
import org.apache.hadoop.io.compress.CompressionCodec;
import org.apache.hadoop.io.compress.CompressionCodecFactory;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.JobContext;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.RecordReader;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.hadoop.mapreduce.lib.input.CombineFileInputFormat;
import org.apache.hadoop.mapreduce.lib.input.CombineFileSplit;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.RDFHandlerException;
import org.eclipse.rdf4j.rio.RDFParser;
import org.eclipse.rdf4j.rio.RDFParserFactory;
import org.eclipse.rdf4j.rio.RDFParserRegistry;
import org.eclipse.rdf4j.rio.Rio;
import org.eclipse.rdf4j.rio.helpers.AbstractRDFHandler;
import org.eclipse.rdf4j.rio.helpers.BasicParserSettings;
import org.eclipse.rdf4j.rio.ntriples.NTriplesUtil;

/**
 * Apache Hadoop MapReduce Tool for bulk loading RDF into HBase
 * @author Adam Sotona (MSD)
 */
public final class HalyardBulkLoad extends AbstractHalyardTool {

    /**
     * Property defining number of bits used for HBase region pre-splits calculation for new table
     */
    public static final String SPLIT_BITS_PROPERTY = "halyard.table.splitbits";

    /**
     * Property truncating existing HBase table just before the bulk load
     */
    public static final String TRUNCATE_PROPERTY = "halyard.table.truncate";

    /**
     * Boolean property skipping RDF parsing errors
     */
    public static final String SKIP_INVALID_PROPERTY = "halyard.parser.skipinvalid";

    /**
     * Boolean property enabling RDF parser verification of data values
     */
    public static final String VERIFY_DATATYPE_VALUES_PROPERTY = "halyard.parser.verify.datatype.values";

    /**
     * Boolean property enforcing triples and quads context override with the default context
     */
    public static final String OVERRIDE_CONTEXT_PROPERTY = "halyard.parser.context.override";

    /**
     * Property defining default context for triples (or even for quads when context override is set)
     */
    public static final String DEFAULT_CONTEXT_PROPERTY = "halyard.parser.context.default";

    /**
     * Property defining exact timestamp of all loaded triples (System.currentTimeMillis() is the default value)
     */
    public static final String DEFAULT_TIMESTAMP_PROPERTY = "halyard.bulk.timestamp";

    /**
     * Mapper class transforming each parsed Statement into set of HBase KeyValues
     */
    public static final class RDFMapper extends Mapper<LongWritable, Statement, ImmutableBytesWritable, KeyValue> {

        private long timestamp;

        @Override
        protected void setup(Context context) throws IOException, InterruptedException {
            Configuration conf = context.getConfiguration();
            timestamp = conf.getLong(DEFAULT_TIMESTAMP_PROPERTY, System.currentTimeMillis());
        }

        @Override
        protected void map(LongWritable key, Statement value, final Context context) throws IOException, InterruptedException {
            for (KeyValue keyValue: HalyardTableUtils.toKeyValues(value.getSubject(), value.getPredicate(), value.getObject(), value.getContext(), false, timestamp)) {
                context.write(new ImmutableBytesWritable(keyValue.getRowArray(), keyValue.getRowOffset(), keyValue.getRowLength()), keyValue);
            }
        }
    }

    /**
     * MapReduce FileInputFormat reading and parsing any RDF4J RIO supported RDF format into Statements
     */
    public static final class RioFileInputFormat extends CombineFileInputFormat<LongWritable, Statement> {

        /**
         * Default constructor of RioFileInputFormat
         */
        public RioFileInputFormat() {
            //this is a workaround to avoid autodetection of .xml files as TriX format and hook on .trix file extension only
            RDFParserRegistry reg = RDFParserRegistry.getInstance();
            Optional<RDFParserFactory> trixPF = reg.get(RDFFormat.TRIX);
            if (trixPF.isPresent()) {
                reg.remove(trixPF.get());
                final RDFParser trixParser = trixPF.get().getParser();
                reg.add(new RDFParserFactory() {
                    @Override
                    public RDFFormat getRDFFormat() {
                        RDFFormat t = RDFFormat.TRIX;
                        return new RDFFormat(t.getName(), t.getMIMETypes(), t.getCharset(), Arrays.asList("trix"), t.getStandardURI(), t.supportsNamespaces(), t.supportsNamespaces());
                    }

                    @Override
                    public RDFParser getParser() {
                        return trixParser;
                    }
                });
            }
        }

        @Override
        protected boolean isSplitable(JobContext context, Path file) {
            return false;
        }

        @Override
        protected List<FileStatus> listStatus(JobContext job) throws IOException {
            List<FileStatus> filteredList = new ArrayList<>();
            for (FileStatus fs : super.listStatus(job)) {
                if (Rio.getParserFormatForFileName(fs.getPath().getName()) != null) {
                    filteredList.add(fs);
                }
            }
            return filteredList;
        }

        @Override
        public RecordReader<LongWritable, Statement> createRecordReader(InputSplit split, TaskAttemptContext context) throws IOException {
            return new RecordReader<LongWritable, Statement>() {

                private final AtomicLong key = new AtomicLong();

                private ParserPump pump = null;
                private Statement current = null;
                private Thread pumpThread = null;

                @Override
                public void initialize(InputSplit split, TaskAttemptContext context) throws IOException, InterruptedException {
                    close();
                    pump = new ParserPump((CombineFileSplit)split, context);
                    pumpThread = new Thread(pump);
                    pumpThread.setDaemon(true);
                    pumpThread.start();
                }

                @Override
                public boolean nextKeyValue() throws IOException, InterruptedException {
                    if (pump == null) return false;
                    current = pump.getNext();
                    key.incrementAndGet();
                    return current != null;
                }

                @Override
                public LongWritable getCurrentKey() throws IOException, InterruptedException {
                    return current == null ? null : new LongWritable(key.get());
                }

                @Override
                public Statement getCurrentValue() throws IOException, InterruptedException {
                    return current;
                }

                @Override
                public float getProgress() throws IOException, InterruptedException {
                    return pump == null ? 0 : pump.getProgress();
                }

                @Override
                public void close() throws IOException {
                    if (pump != null) {
                        pump.close();
                        pump = null;
                    }
                    if (pumpThread != null) {
                        pumpThread.interrupt();
                        pumpThread = null;
                    }
                }
            };
        }
    }

    private static final IRI NOP = SimpleValueFactory.getInstance().createIRI(":");
    private static final Statement END_STATEMENT = SimpleValueFactory.getInstance().createStatement(NOP, NOP, NOP);

    private static final class ParserPump extends AbstractRDFHandler implements Closeable, Runnable {
        private final TaskAttemptContext context;
        private final Path paths[];
        private final long size;
        private final SynchronousQueue<Statement> queue = new SynchronousQueue<>();
        private final boolean skipInvalid, verifyDataTypeValues;
        private final String defaultRdfContextPattern;
        private final boolean overrideRdfContext;
        private Exception ex = null;
        private long finishedSize = 0;

        private String baseUri = "";
        private Seekable seek;
        private InputStream in;

        public ParserPump(CombineFileSplit split, TaskAttemptContext context) {
            this.context = context;
            this.paths = split.getPaths();
            this.size = split.getLength();
            Configuration conf = context.getConfiguration();
            this.skipInvalid = conf.getBoolean(SKIP_INVALID_PROPERTY, false);
            this.verifyDataTypeValues = conf.getBoolean(VERIFY_DATATYPE_VALUES_PROPERTY, false);
            this.overrideRdfContext = conf.getBoolean(OVERRIDE_CONTEXT_PROPERTY, false);
            this.defaultRdfContextPattern = conf.get(DEFAULT_CONTEXT_PROPERTY);
        }

        public Statement getNext() throws IOException, InterruptedException {
            Statement s = queue.take();
            if (ex != null) synchronized (this) {
                throw new IOException("Exception while parsing: " + baseUri, ex);
            }
            return s == END_STATEMENT ? null : s;
        }

        public synchronized float getProgress() {
            try {
                return (float)(finishedSize + seek.getPos()) / (float)size;
            } catch (IOException e) {
                return (float)finishedSize / (float)size;
            }
        }

        @Override
        public void run() {
            try {
                Configuration conf = context.getConfiguration();
                for (Path file : paths) try {
                    RDFParser parser;
                    InputStream localIn;
                    final String localBaseUri;
                    synchronized (this) {
                        if (seek != null) try {
                            finishedSize += seek.getPos();
                        } catch (IOException e) {
                            //ignore
                        }
                        close();
                        this.baseUri = file.toString();
                        context.setStatus("Parsing " + baseUri);
                        FileSystem fs = file.getFileSystem(conf);
                        FSDataInputStream fileIn = fs.open(file);
                        this.seek = fileIn;
                        CompressionCodec codec = new CompressionCodecFactory(conf).getCodec(file);
                        if (codec != null) {
                            this.in = codec.createInputStream(fileIn, CodecPool.getDecompressor(codec));
                        } else {
                            this.in = fileIn;
                        }
                        parser = Rio.createParser(Rio.getParserFormatForFileName(baseUri).get());
                        parser.setRDFHandler(this);
                        parser.setStopAtFirstError(!skipInvalid);
                        parser.set(BasicParserSettings.VERIFY_DATATYPE_VALUES, verifyDataTypeValues);
                        localIn = this.in; //synchronised parameters must be copied to a local variable for use outide of sync block
                        localBaseUri = this.baseUri;
                        if (defaultRdfContextPattern != null || overrideRdfContext) {
                            final IRI defaultRdfContext = defaultRdfContextPattern == null ? null : SimpleValueFactory.getInstance().createIRI(MessageFormat.format(defaultRdfContextPattern, localBaseUri, file.toUri().getPath(), file.getName()));
                            parser.setValueFactory(new SimpleValueFactory(){
                                @Override
                                public Statement createStatement(Resource subject, IRI predicate, Value object) {
                                    return defaultRdfContextPattern != null ?  super.createStatement(subject, predicate, object, defaultRdfContext) : super.createStatement(subject, predicate, object);
                                }

                                @Override
                                public Statement createStatement(Resource subject, IRI predicate, Value object, Resource context) {
                                    return overrideRdfContext ? createStatement(subject, predicate, object) : super.createStatement(subject, predicate, object, context);
                                }
                            });
                        }
                    }
                    parser.parse(localIn, localBaseUri);
                } catch (Exception e) {
                    if (skipInvalid) {
                        LOG.log(Level.WARNING, "Exception while parsing RDF", e);
                    } else {
                        throw e;
                    }
                }
            } catch (Exception e) {
                ex = e;
            } finally {
                try {
                    queue.put(END_STATEMENT);
                } catch (InterruptedException ignore) {}
            }
        }

        @Override
        public void handleStatement(Statement st) throws RDFHandlerException {
            try {
                queue.put(st);
            } catch (InterruptedException e) {
                throw new RDFHandlerException(e);
            }
        }

        @Override
        public synchronized void close() throws IOException {
            if (in != null) {
                in.close();
                in = null;
            }
        }
    }

    private static String listRDF() {
        StringBuilder sb = new StringBuilder();
        for (RDFFormat fmt : RDFParserRegistry.getInstance().getKeys()) {
            sb.append("* ").append(fmt.getName()).append(" (");
            boolean first = true;
            for (String ext : fmt.getFileExtensions()) {
                if (first) {
                    first = false;
                } else {
                    sb.append(", ");
                }
                sb.append('.').append(ext);
            }
            sb.append(")\n");
        }
        return sb.toString();
    }

    public HalyardBulkLoad() {
        super(
            "bulkload",
            "Halyard Bulk Load is a MapReduce application designed to efficiently load RDF data from Hadoop Filesystem (HDFS) into HBase in the form of a Halyard dataset.",
            "Halyard Bulk Load consumes RDF files in various formats supported by RDF4J RIO, including:\n"
                + listRDF()
                + "All the supported RDF formats can be also compressed with one of the compression codecs supported by Hadoop, including:\n"
                + "* Gzip (.gz)\n"
                + "* Bzip2 (.bz2)\n"
                + "* LZO (.lzo)\n"
                + "* Snappy (.snappy)\n"
                + "Example: halyard bulkload -s hdfs://my_RDF_files -w hdfs:///my_tmp_workdir -t mydataset"
        );
        addOption("s", "source", "source_paths", "Source path(s) with RDF files, more paths can be delimited by comma, the paths are searched for the supported files recurrently", true, true);
        addOption("w", "work-dir", "shared_folder", "Unique non-existent folder within shared filesystem to server as a working directory for the temporary HBase files,  the files are moved to their final HBase locations during the last stage of the load process", true, true);
        addOption("t", "target", "dataset_table", "Target HBase table with Halyard RDF store", true, true);
        addOption("i", "skip-invalid", null, "Optionally skip invalid source files and parsing errors", false, false);
        addOption("d", "verify-data-types", null, "Optionally verify RDF data type values while parsing", false, false);
        addOption("r", "truncate-target", null, "Optionally truncate target table just before the loading the new data", false, false);
        addOption("b", "pre-split-bits", "bits", "Optionally specify bit depth of region pre-splits for a case when target table does not exist (default is 3)", false, true);
        addOption("g", "graph-context", "uri_pattern", "Optionally specify default target named graph context. URI pattern may include {0} token to be replaced with full source file URI, token {1} with just the path of the file, and {2} with the file name", false, true);
        addOption("o", "graph-context-override", null, "Optionally override named graph context also for loaded quads", false, false);
        addOption("e", "target-timestamp", "timestamp", "Optionally specify timestamp of all loaded records (defaul is actual time of the operation)", false, true);
    }

    @Override
    protected int run(CommandLine cmd) throws Exception {
        String source = cmd.getOptionValue('s');
        String workdir = cmd.getOptionValue('w');
        String target = cmd.getOptionValue('t');
        getConf().setBoolean(SKIP_INVALID_PROPERTY, cmd.hasOption('i'));
        getConf().setBoolean(VERIFY_DATATYPE_VALUES_PROPERTY, cmd.hasOption('d'));
        getConf().setBoolean(TRUNCATE_PROPERTY, cmd.hasOption('r'));
        getConf().setInt(SPLIT_BITS_PROPERTY, Integer.parseInt(cmd.getOptionValue('b', "3")));
        if (cmd.hasOption('g')) getConf().set(DEFAULT_CONTEXT_PROPERTY, cmd.getOptionValue('g'));
        getConf().setBoolean(OVERRIDE_CONTEXT_PROPERTY, cmd.hasOption('o'));
        getConf().setLong(DEFAULT_TIMESTAMP_PROPERTY, Long.parseLong(cmd.getOptionValue('e', String.valueOf(System.currentTimeMillis()))));
        TableMapReduceUtil.addDependencyJars(getConf(),
                NTriplesUtil.class,
                Rio.class,
                AbstractRDFHandler.class,
                RDFFormat.class,
                RDFParser.class);
        HBaseConfiguration.addHbaseResources(getConf());
        Job job = Job.getInstance(getConf(), "HalyardBulkLoad -> " + workdir + " -> " + target);
        job.setJarByClass(HalyardBulkLoad.class);
        job.setMapperClass(RDFMapper.class);
        job.setMapOutputKeyClass(ImmutableBytesWritable.class);
        job.setMapOutputValueClass(KeyValue.class);
        job.setInputFormatClass(RioFileInputFormat.class);
        job.setSpeculativeExecution(false);
        job.setReduceSpeculativeExecution(false);
        try (HTable hTable = HalyardTableUtils.getTable(getConf(), target, true, getConf().getInt(SPLIT_BITS_PROPERTY, 3))) {
            HFileOutputFormat2.configureIncrementalLoad(job, hTable.getTableDescriptor(), hTable.getRegionLocator());
            FileInputFormat.setInputDirRecursive(job, true);
            FileInputFormat.setInputPaths(job, source);
            FileOutputFormat.setOutputPath(job, new Path(workdir));
            TableMapReduceUtil.addDependencyJars(job);
            TableMapReduceUtil.initCredentials(job);
            if (job.waitForCompletion(true)) {
                if (getConf().getBoolean(TRUNCATE_PROPERTY, false)) {
                    HalyardTableUtils.truncateTable(hTable).close();
                }
                new LoadIncrementalHFiles(getConf()).doBulkLoad(new Path(workdir), hTable);
                LOG.info("Bulk Load Completed..");
                return 0;
            }
        }
        return -1;
    }
}
