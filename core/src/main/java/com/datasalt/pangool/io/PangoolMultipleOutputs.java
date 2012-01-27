/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.datasalt.pangool.io;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringTokenizer;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.JobContext;
import org.apache.hadoop.mapreduce.OutputCommitter;
import org.apache.hadoop.mapreduce.OutputFormat;
import org.apache.hadoop.mapreduce.RecordWriter;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.hadoop.mapreduce.TaskInputOutputContext;
import org.apache.hadoop.util.ReflectionUtils;

import com.datasalt.pangool.api.ProxyOutputFormat.ProxyOutputCommitter;

/**
 * This class is inspired by the MultipleOutputs class of Hadoop.
 * The difference is that it allows an arbitrary OutputFormat to be written in sub-folders of the output path.
 **/
@SuppressWarnings("rawtypes")
public class PangoolMultipleOutputs<KEYOUT, VALUEOUT> {

	private static final String MULTIPLE_OUTPUTS = "pangool.multipleoutputs";

	private static final String MO_PREFIX = "pangool.multipleoutputs.namedOutput.";

	private static final String FORMAT = ".format";
	private static final String KEY = ".key";
	private static final String VALUE = ".value";
	private static final String CONF = ".conf"; // Added to allow specific Configuration properties for named outputs

	private static final String COUNTERS_ENABLED = "pangool.multipleoutputs.counters";

	/**
	 * Counters group used by the counters of MultipleOutputs.
	 */
	private static final String COUNTERS_GROUP = PangoolMultipleOutputs.class.getName();

	/**
	 * Checks if a named output name is valid token.
	 * 
	 * @param namedOutput
	 *          named output Name
	 * @throws IllegalArgumentException
	 *           if the output name is not valid.
	 */
	private static void checkTokenName(String namedOutput) {
		if(namedOutput == null || namedOutput.length() == 0) {
			throw new IllegalArgumentException("Name cannot be NULL or emtpy");
		}
		for(char ch : namedOutput.toCharArray()) {
			if((ch >= 'A') && (ch <= 'Z')) {
				continue;
			}
			if((ch >= 'a') && (ch <= 'z')) {
				continue;
			}
			if((ch >= '0') && (ch <= '9')) {
				continue;
			}
			throw new IllegalArgumentException("Name cannot be have a '" + ch + "' char");
		}
	}

	/**
	 * Checks if output name is valid.
	 * 
	 * name cannot be the name used for the default output
	 * 
	 * @param outputPath
	 *          base output Name
	 * @throws IllegalArgumentException
	 *           if the output name is not valid.
	 */
	private static void checkBaseOutputPath(String outputPath) {
		if(outputPath.equals("part")) {
			throw new IllegalArgumentException("output name cannot be 'part'");
		}
	}

	/**
	 * Checks if a named output name is valid.
	 * 
	 * @param namedOutput
	 *          named output Name
	 * @throws IllegalArgumentException
	 *           if the output name is not valid.
	 */
	private static void checkNamedOutputName(JobContext job, String namedOutput, boolean alreadyDefined) {
		validateOutputName(namedOutput);
		List<String> definedChannels = getNamedOutputsList(job);
		if(alreadyDefined && definedChannels.contains(namedOutput)) {
			throw new IllegalArgumentException("Named output '" + namedOutput + "' already alreadyDefined");
		} else if(!alreadyDefined && !definedChannels.contains(namedOutput)) {
			throw new IllegalArgumentException("Named output '" + namedOutput + "' not defined");
		}
	}

	/**
	 * Convenience method for validating output names externally.Will throw InvalidArgumentException if parameter name is
	 * not a valid output name according to this implementation.
	 * 
	 * @param namedOutput
	 *          the name to validate (e.g. "part" not allowed)
	 */
	public static void validateOutputName(String namedOutput) {
		checkTokenName(namedOutput);
		checkBaseOutputPath(namedOutput);
	}

	// Returns list of channel names.
	private static List<String> getNamedOutputsList(JobContext job) {
		List<String> names = new ArrayList<String>();
		StringTokenizer st = new StringTokenizer(job.getConfiguration().get(MULTIPLE_OUTPUTS, ""), " ");
		while(st.hasMoreTokens()) {
			names.add(st.nextToken());
		}
		return names;
	}

	// Returns the named output OutputFormat.
	@SuppressWarnings("unchecked")
	private static Class<? extends OutputFormat<?, ?>> getNamedOutputFormatClass(JobContext job, String namedOutput) {
		return (Class<? extends OutputFormat<?, ?>>) job.getConfiguration().getClass(MO_PREFIX + namedOutput + FORMAT,
		    null, OutputFormat.class);
	}

	// Returns the key class for a named output.
	private static Class<?> getNamedOutputKeyClass(JobContext job, String namedOutput) {
		return job.getConfiguration().getClass(MO_PREFIX + namedOutput + KEY, null, Object.class);
	}

	// Returns the value class for a named output.
	private static Class<?> getNamedOutputValueClass(JobContext job, String namedOutput) {
		return job.getConfiguration().getClass(MO_PREFIX + namedOutput + VALUE, null, Object.class);
	}

	/**
	 * Adds a named output for the job.
	 * <p/>
	 * 
	 * @param job
	 *          job to add the named output
	 * @param namedOutput
	 *          named output name, it has to be a word, letters and numbers only, cannot be the word 'part' as that is
	 *          reserved for the default output.
	 * @param outputFormatClass
	 *          OutputFormat class.
	 * @param keyClass
	 *          key class
	 * @param valueClass
	 *          value class
	 */
	public static void addNamedOutput(Job job, String namedOutput, Class<? extends OutputFormat> outputFormatClass,
	    Class<?> keyClass, Class<?> valueClass) {
		checkNamedOutputName(job, namedOutput, true);
		Configuration conf = job.getConfiguration();
		conf.set(MULTIPLE_OUTPUTS, conf.get(MULTIPLE_OUTPUTS, "") + " " + namedOutput);
		conf.setClass(MO_PREFIX + namedOutput + FORMAT, outputFormatClass, OutputFormat.class);
		conf.setClass(MO_PREFIX + namedOutput + KEY, keyClass, Object.class);
		conf.setClass(MO_PREFIX + namedOutput + VALUE, valueClass, Object.class);
	}

	/**
	 * Added this method for allowing specific (key, value) configurations for each Output. Some Output Formats read
	 * specific configuration values and act based on them.
	 * 
	 * @param namedOutput
	 * @param key
	 * @param value
	 */
	@SuppressWarnings("unchecked")
	public static void addNamedOutputContext(Job job, String namedOutput, String key, String value) {
		// Check that this named output has been configured before
		Configuration conf = job.getConfiguration();
		Class<? extends OutputFormat> outputFormatClass = (Class<? extends OutputFormat>) conf.getClass(MO_PREFIX
		    + namedOutput + FORMAT, null);
		if(outputFormatClass == null) {
			throw new IllegalArgumentException("Undefined named output '" + namedOutput + "'");
		}
		// Add specific configuration
		conf.set(MO_PREFIX + namedOutput + CONF + "." + key, value);
	}

	/**
	 * Iterates over the Configuration and sets the specific context found for the namedOutput in the Job instance.
	 * Package-access so it can be unit tested. The specific context is configured in method this.
	 * {@link #addNamedOutputContext(Job, String, String, String)}.
	 * 
	 * @param conf
	 *          The configuration that may contain specific context for the named output
	 * @param job
	 *          The Job where we will set the specific context
	 * @param namedOutput
	 *          The named output
	 */
	static void setSpecificNamedOutputContext(Configuration conf, Job job, String namedOutput) {
		for(Map.Entry<String, String> entries : conf) {
			String confKey = entries.getKey();
			String confValue = entries.getValue();
			if(confKey.startsWith(MO_PREFIX + namedOutput + CONF)) {
				// Specific context key, value found
				String contextKey = confKey.substring((MO_PREFIX + namedOutput + CONF + ".").length(), confKey.length());
				job.getConfiguration().set(contextKey, confValue);
			}
		}
	}

	/**
	 * Enables or disables counters for the named outputs.
	 * 
	 * The counters group is the {@link PangoolMultipleOutputs} class name. The names of the counters are the same as the
	 * named outputs. These counters count the number records written to each output name. By default these counters are
	 * disabled.
	 * 
	 * @param job
	 *          job to enable counters
	 * @param enabled
	 *          indicates if the counters will be enabled or not.
	 */
	public static void setCountersEnabled(Job job, boolean enabled) {
		job.getConfiguration().setBoolean(COUNTERS_ENABLED, enabled);
	}

	/**
	 * Returns if the counters for the named outputs are enabled or not. By default these counters are disabled.
	 * 
	 * @param job
	 *          the job
	 * @return TRUE if the counters are enabled, FALSE if they are disabled.
	 */
	public static boolean getCountersEnabled(JobContext job) {
		return job.getConfiguration().getBoolean(COUNTERS_ENABLED, false);
	}

	/**
	 * Wraps RecordWriter to increment counters.
	 */
	@SuppressWarnings("unchecked")
	private static class RecordWriterWithCounter extends RecordWriter {
		private RecordWriter writer;
		private String counterName;
		private TaskInputOutputContext context;

		public RecordWriterWithCounter(RecordWriter writer, String counterName, TaskInputOutputContext context) {
			this.writer = writer;
			this.counterName = counterName;
			this.context = context;
		}

		public void write(Object key, Object value) throws IOException, InterruptedException {
			context.getCounter(COUNTERS_GROUP, counterName).increment(1);
			writer.write(key, value);
		}

		public void close(TaskAttemptContext context) throws IOException, InterruptedException {
			writer.close(context);
		}
	}

	// instance code, to be used from Mapper/Reducer code

	private TaskInputOutputContext<?, ?, KEYOUT, VALUEOUT> context;
	private Set<String> namedOutputs;
	private Map<String, OutputContext> outputContexts;
	private boolean countersEnabled;
	
	private static class OutputContext {
		RecordWriter recordWriter;
		TaskAttemptContext taskAttemptContext;
		JobContext jobContext;
		OutputCommitter outputCommitter;
	}

	/**
	 * Creates and initializes multiple outputs support, it should be instantiated in the Mapper/Reducer setup method.
	 * 
	 * @param context
	 *          the TaskInputOutputContext object
	 */
	public PangoolMultipleOutputs(TaskInputOutputContext<?, ?, KEYOUT, VALUEOUT> context) {
		this.context = context;
		namedOutputs = Collections
		    .unmodifiableSet(new HashSet<String>(PangoolMultipleOutputs.getNamedOutputsList(context)));
		outputContexts = new HashMap<String, OutputContext>();
		countersEnabled = getCountersEnabled(context);
	}

	/**
	 * Write key and value to the namedOutput.
	 * 
	 * Output path is a unique file generated for the namedOutput. For example, {namedOutput}-(m|r)-{part-number}
	 * 
	 * @param namedOutput
	 *          the named output name
	 * @param key
	 *          the key
	 * @param value
	 *          the value
	 */
	public <K, V> void write(String namedOutput, K key, V value) throws IOException, InterruptedException {
		write(namedOutput, key, value, namedOutput);
	}

	/**
	 * Write key and value to baseOutputPath using the namedOutput.
	 * 
	 * @param namedOutput
	 *          the named output name
	 * @param key
	 *          the key
	 * @param value
	 *          the value
	 * @param baseOutputPath
	 *          base-output path to write the record to. Note: Framework will generate unique filename for the
	 *          baseOutputPath
	 */
	@SuppressWarnings("unchecked")
	public <K, V> void write(String namedOutput, K key, V value, String baseOutputPath) throws IOException,
	    InterruptedException {
		checkNamedOutputName(context, namedOutput, false);
		checkBaseOutputPath(baseOutputPath);
		if(!namedOutputs.contains(namedOutput)) {
			throw new IllegalArgumentException("Undefined named output '" + namedOutput + "'");
		}
		getRecordWriter(baseOutputPath).write(key, value);
	}

	// by being synchronized MultipleOutputTask can be use with a
	// MultithreadedMapper.
	public synchronized RecordWriter getRecordWriter(String baseFileName)
	    throws IOException, InterruptedException {

		// Look for record-writer in the cache
		OutputContext context = outputContexts.get(baseFileName);

		// If not in cache, create a new one
		if(context == null) {
			
			context = new OutputContext();

			OutputFormat mainOutputFormat;
			
			try {
				mainOutputFormat = ((OutputFormat) ReflectionUtils.newInstance(this.context.getOutputFormatClass(),
	      		this.context.getConfiguration()));
      } catch(ClassNotFoundException e1) {
	      throw new RuntimeException(e1);
      }
      
      ProxyOutputCommitter baseOutputCommitter = ((ProxyOutputCommitter)mainOutputFormat.getOutputCommitter(this.context));

			// The trick is to create a new Job for each output
			Job job = new Job(this.context.getConfiguration());
			job.setOutputFormatClass(getNamedOutputFormatClass(this.context, baseFileName));
			job.setOutputKeyClass(getNamedOutputKeyClass(this.context, baseFileName));
			job.setOutputValueClass(getNamedOutputValueClass(this.context, baseFileName));
			// Check possible specific context for the output
			setSpecificNamedOutputContext(this.context.getConfiguration(), job, baseFileName);
			TaskAttemptContext taskContext = new TaskAttemptContext(job.getConfiguration(), this.context.getTaskAttemptID());
						
			// First we change the output dir for the new OutputFormat that we will create 
			// We put it inside the main output work path -> in case the Job fails, everything will be discarded
			taskContext.getConfiguration().set("mapred.output.dir", baseOutputCommitter.getBaseDir() + "/" + baseFileName);
			context.taskAttemptContext = taskContext;

			try {
				// Create the new output format with ReflectionUtils
				OutputFormat outputFormat = ((OutputFormat) ReflectionUtils.newInstance(taskContext.getOutputFormatClass(),
				    taskContext.getConfiguration()));
				// We have to create a JobContext for meeting the contract of the OutputFormat
				JobContext jobContext = new JobContext(taskContext.getConfiguration(), taskContext.getJobID());
				context.jobContext = jobContext;
				// The contract of the OutputFormat is to check the output specs
				outputFormat.checkOutputSpecs(jobContext);
				// We get the output committer so we can call it later 
				context.outputCommitter = outputFormat.getOutputCommitter(taskContext);
				// Save the RecordWriter to cache it
				context.recordWriter = outputFormat.getRecordWriter(taskContext);
			} catch(ClassNotFoundException e) {
				throw new IOException(e);
			}

			// if counters are enabled, wrap the writer with context
			// to increment counters
			if(countersEnabled) {
				context.recordWriter = new RecordWriterWithCounter(context.recordWriter, baseFileName, this.context);
			}
			
			outputContexts.put(baseFileName, context);
		}
		return context.recordWriter;
	}

	/**
	 * Closes all the opened outputs.
	 * 
	 * This should be called from cleanup method of map/reduce task. If overridden subclasses must invoke
	 * <code>super.close()</code> at the end of their <code>close()</code>
	 * 
	 */
	public void close() throws IOException, InterruptedException {
		for(OutputContext outputContext: this.outputContexts.values()) {
			outputContext.recordWriter.close(outputContext.taskAttemptContext);
			outputContext.outputCommitter.commitTask(outputContext.taskAttemptContext);
			outputContext.outputCommitter.cleanupJob(outputContext.jobContext);
		}
	}
}