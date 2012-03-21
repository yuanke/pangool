/**
 * Copyright [2012] [Datasalt Systems S.L.]
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.datasalt.pangool.flow;


import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.util.ToolRunner;
import org.mortbay.log.Log;

import com.datasalt.pangool.utils.HadoopUtils;

/**
 * This class allows creating classes that define a Flow by defining resources and binding relationships between them. 
 * <p>
 * Use {@link #add(FlowJob)} for adding Job resources to the Flow. Resources must implement {@link FlowJob} for declaring
 * its input / output / parameter dependencies.
 * <p>
 * Use {@link #add(String)} for adding Input resources to the Flow (e.g. input files).
 * <p>
 * Use {@link #bind(String, Object)} for binding relations between resources. The convention to use is : resourceName.resource.
 * For instance, the output of Job1 is "Job1.output" (given that Job1.name() == "Job1").
 * <p>
 * This implementation executes the defined flow as a linear topology.
 * <p>
 * See {@link ExampleFlow} for an example.
 */
public abstract class LinearFlow {

	List<String> inputs = new ArrayList<String>();
	
	Map<String, String> bindings = new HashMap<String, String>();
	Map<String, FlowJob> jobContext = new HashMap<String, FlowJob>();
	
	public void add(FlowJob job) {
		if(jobContext.containsKey(job.getName())) {
			throw new IllegalArgumentException(job.getName() + " already binded to an instance of " + jobContext.get(job.getName()).getClass().getName());
		}
		jobContext.put(job.getName(), job);
	}
	
	public void add(String input) {
		inputs.add(input);
	}
	
	public void bind(String name, Object bind) {
		if(bindings.get(name) != null) {
			throw new IllegalArgumentException("Property [" + name + "] already binded to " + bindings.get(name));
		}
		bindings.put(name, bind.toString());
	}
	
	public FlowJob findInOutputs(String output) {
		for(FlowJob job: jobContext.values()) {
			if(output.equals(job.getOutputName())) {
				return job;
			}
			for(String namedOutput: job.getNamedOutputs()) {
				if(job.getNamedOutputName(namedOutput).equals(output)) {
					return job;
				}
			}
		}		
		return null;
	}
	
	public static enum EXECUTION_MODE {
		OVERWRITE, POLITE
	}
	
	public void execute(EXECUTION_MODE mode, Configuration conf, String... outputs) throws Exception {
		List<FlowJob> toExecute = new ArrayList<FlowJob>();
		List<FlowJob> toResolve = new ArrayList<FlowJob>();
		
		for(String output: outputs) {
			FlowJob orig = findInOutputs(output);
			if(orig == null) {
				throw new IllegalArgumentException("Unknown output: " + output + " not found in flow context.");
			}
			
			toResolve.add(orig);
		}
		
		FlowJob orig ;
		
		while(toResolve.size() > 0) {
			Iterator<FlowJob> it = toResolve.iterator();
			orig = it.next();
			it.remove();
			Log.info("Resolving dependencies for " + orig.getName());
			if(toExecute.contains(orig)) { // TODO Explain this and test it better
				toExecute.remove(orig);
			}
			toExecute.add(orig);
			
			for(Input input: orig.getInputs()) {
				String inputName = orig.getName() + "." + input.name; 
				String bindedTo = bindings.get(inputName);
				
				if(bindedTo == null) {
					throw new IllegalArgumentException("Input " + inputName+ " not binded to anything in current flow context.");
				}
				
				FlowJob job = findInOutputs(bindedTo);
				if(job == null) {
					if(!inputs.contains(bindedTo)) {
						throw new IllegalArgumentException("Unknown input: " + bindedTo + " binded to " + inputName + " not found in flow context.");
					}
				} else {
					if(job.getNamedOutputs().size() > 0 && bindedTo.endsWith(".output")) {
						// Main output from a Job with multiple outputs. Rebind to glob.
						bindings.put(inputName, bindedTo + "/part*");
					} else if(!bindedTo.endsWith(".output")) {
						// Named output. Rebind to corresponding glob.
						int lastPoint = bindedTo.lastIndexOf(".");
						String namedOutput = bindedTo.substring(lastPoint + 1, bindedTo.length());
						bindings.put(inputName, bindedTo.substring(0, lastPoint) + "/" + namedOutput);
					}
					toResolve.add(job);
				}
			}
		}
		
		Log.info("Linear execution plan: " + toExecute);
		
		for(int i = toExecute.size() - 1; i >= 0; i--) {
			FlowJob job = toExecute.get(i);
			List<String> args = new ArrayList<String>();
			for(Param param: job.getParameters()) {
				String paramName = job.getName() + "." + param.getName();
				args.add("-D");
				args.add(paramName + "=" + bindings.get(paramName));
			}
			for(Input input: job.getInputs()) {
				String inputName = job.getName() + "." + input.name;
				args.add("--" + input.name);
				String bindedTo = bindings.get(inputName);
				args.add(bindedTo);
			}
			args.add("--output");
			// Output = outputName if it's not binded
			String bindedTo = bindings.get(job.getOutputName());
			if(bindedTo == null) {
				bindedTo = job.getOutputName();
			} 
			args.add(bindedTo);
			if(mode.equals(EXECUTION_MODE.OVERWRITE)) {
				Path p = new Path(bindedTo);
				HadoopUtils.deleteIfExists(p.getFileSystem(conf), p);
			}
			Log.info("Executing [" + job.getName() + "], args: " + args);
			ToolRunner.run(conf, job, args.toArray(new String[0]));
		}
	}
}
