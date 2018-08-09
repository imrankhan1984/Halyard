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

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.logging.Logger;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.cli.PosixParser;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.util.Tool;

/**
 *
 * @author Adam Sotona (MSD)
 */
public abstract class HalyardTool implements Tool {

    static final Logger LOG = Logger.getLogger(HalyardTool.class.getName());

    private Configuration conf = new Configuration();
    private final String name, header, footer;
    private final Options options = new Options();
    private final List<String> singleOptions = new ArrayList<>();
    private final List<String> requiredOptions = new ArrayList<>();

    protected HalyardTool(String name, String header, String footer) {
        this.name = name;
        this.header = header;
        this.footer = footer;
        addOption("h", "help", null, "Prints this help", false, false);
        addOption("v", "version", null, "Prints version", false, false);
    }

    protected final void printHelp() {
        new HelpFormatter().printHelp(100, name, header, options, footer, true);
    }

    @Override
    public final Configuration getConf() {
        return this.conf;
    }

    @Override
    public final void setConf(final Configuration c) {
        this.conf = c;
    }

    protected final void addOption(String opt, String longOpt, String argName, String description, boolean required, boolean single) {
        Option o = new Option(opt, longOpt, argName != null, description);
        o.setArgName(argName);
        options.addOption(o);
        if (single) {
            singleOptions.add(opt == null ? longOpt : opt);
        }
        if (required) {
            requiredOptions.add(opt == null ? longOpt : opt);
        }
    }

    protected final String addTmpFile(String file) throws IOException {
        String tmpFiles = conf.get("tmpfiles");
        Path path = resolveFile(file);
        conf.set("tmpfiles", tmpFiles == null ? path.toString() : tmpFiles + "," + path.toString());
        return path.getName();
    }

    protected final Path resolveFile(String file) throws IOException {
        URI pathURI;
        try {
            pathURI = new URI(file);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(e);
        }
        Path path = new Path(pathURI);
        FileSystem localFs = FileSystem.getLocal(conf);
        if (pathURI.getScheme() == null) {
            if (!localFs.exists(path)) {
                throw new FileNotFoundException("File " + file + " does not exist.");
            }
            return path.makeQualified(localFs.getUri(), localFs.getWorkingDirectory());
        } else {
            FileSystem fs = path.getFileSystem(conf);
            if (!fs.exists(path)) {
                throw new FileNotFoundException("File " + file + " does not exist.");
            }
            return path.makeQualified(fs.getUri(), fs.getWorkingDirectory());
        }
    }

    protected abstract int run(CommandLine cmd) throws Exception;

    @Override
    public final int run(String[] args) throws Exception {
        try {
            CommandLine cmd = new PosixParser().parse(options, args);
            if (args.length == 0 || cmd.hasOption('h')) {
                printHelp();
                return -1;
            }
            if (cmd.hasOption('v')) {
                Properties p = new Properties();
                try (InputStream in = HalyardExport.class.getResourceAsStream("/META-INF/maven/com.msd.gin.halyard/halyard-tools/pom.properties")) {
                    if (in != null) p.load(in);
                }
                System.out.println(name + " version " + p.getProperty("version", "unknown"));
                return 0;
            }
            if (!cmd.getArgList().isEmpty()) {
                throw new ParseException("Unknown arguments: " + cmd.getArgList().toString());
            }
            for (String opt : requiredOptions) {
                if (!cmd.hasOption(opt))  throw new ParseException("Missing mandatory option: " + opt);
            }
            for (String opt : singleOptions) {
                String s[] = cmd.getOptionValues(opt);
                if (s != null && s.length > 1)  throw new ParseException("Multiple values for option: " + opt);
            }
            return run(cmd);
        } catch (Exception exp) {
            System.out.println(exp.getMessage());
            printHelp();
            throw exp;
        }

    }
}
