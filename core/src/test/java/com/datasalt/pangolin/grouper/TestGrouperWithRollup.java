/**
 * Copyright [2011] [Datasalt Systems S.L.]
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

package com.datasalt.pangolin.grouper;

import java.io.IOException;
import java.util.Iterator;

import junit.framework.Assert;

import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.SequenceFile;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.hadoop.mapreduce.lib.input.SequenceFileInputFormat;
import org.apache.hadoop.mapreduce.lib.output.SequenceFileOutputFormat;
import org.junit.Test;

import com.datasalt.pangolin.commons.test.AbstractHadoopTestLibrary;
import com.datasalt.pangolin.grouper.io.tuple.ITuple;
import com.datasalt.pangolin.grouper.io.tuple.ITuple.InvalidFieldException;
import com.datasalt.pangolin.grouper.io.tuple.DoubleBufferPangolinTuple;
import com.datasalt.pangolin.grouper.mapreduce.InputProcessor;
import com.datasalt.pangolin.grouper.mapreduce.handler.GroupHandler;


public class TestGrouperWithRollup extends AbstractHadoopTestLibrary{

	private static class Mapy extends InputProcessor<Text,NullWritable>{
		

		private Schema schema;
		
		
    @SuppressWarnings("unchecked")
		@Override
		public void setup(Schema schema,Context context) throws IOException,InterruptedException,GrouperException  {
			this.schema = schema;
			
			
		}
		
		
		@SuppressWarnings("unchecked")
		@Override
		public void process(Text key,NullWritable value,Collector collector) throws IOException,InterruptedException{
			try {
				DoubleBufferPangolinTuple outputKey = createTuple(key.toString(), schema);
				collector.write(outputKey);
			} catch (InvalidFieldException e) {
				throw new RuntimeException(e);
			}
		}
	}
	
	private static class IdentityRed extends GroupHandler<Text,Text>{

		//private Reducer.Context context;
		private Text outputKey = new Text();
		private Text outputValue = new Text();
		
		@Override
		public void setup(Schema schema,@SuppressWarnings("rawtypes") Reducer.Context context) throws IOException,InterruptedException {
			//this.context = context;
		}
		

		@Override
		public void cleanup(Schema schema,Reducer.Context context) throws IOException,InterruptedException {
			
		}
		
		@SuppressWarnings("unchecked")
		@Override
    public void onOpenGroup(int depth,String field,ITuple firstElement,Reducer.Context context) throws IOException, InterruptedException {
			outputKey.set("OPEN "+ depth);
			outputValue.set(firstElement.toString());
	    context.write(outputKey, outputValue);
	    System.out.println(outputKey +" => " + outputValue);
    }

		@SuppressWarnings("unchecked")
		@Override
    public void onCloseGroup(int depth,String field,ITuple lastElement,Reducer.Context context) throws IOException, InterruptedException {
			outputKey.set("CLOSE "+ depth);
			outputValue.set(lastElement.toString());
	    context.write(outputKey, outputValue);
	    System.out.println(outputKey +" => " + outputValue);
    }
		
		@SuppressWarnings("unchecked")
		@Override
		public void onGroupElements(Iterable<ITuple> tuples,Reducer.Context context) throws IOException,InterruptedException {
			Iterator<ITuple> iterator = tuples.iterator();
			outputKey.set("ELEMENT");
			while ( iterator.hasNext()){
				ITuple tuple = iterator.next();
				outputValue.set(tuple.toString());
				context.write(outputKey,outputValue);
		    System.out.println(outputKey +" => " + outputValue);
			}
	  }
	}
	
	
	private static DoubleBufferPangolinTuple createTuple(String text,Schema schema) throws InvalidFieldException{
		DoubleBufferPangolinTuple tuple = new DoubleBufferPangolinTuple();
		String[] tokens = text.split("\\s+");
		String country = tokens[0];
		Integer age = Integer.parseInt(tokens[1]);
		String name = tokens[2];
		Integer height = Integer.parseInt(tokens[3]);
		
		tuple.setString("country",country);
		tuple.setInt("age",age);
		tuple.setString("name",name);
		tuple.setInt("height", height);
		return tuple;
	}
	
	@Test
	public void test() throws IOException, InterruptedException, ClassNotFoundException, GrouperException, InstantiationException, IllegalAccessException{
		
		String[] inputElements = new String[]{
				"ES 20 listo 250",
				"US 14 beber 202",
				"US 14 perro 180",
				"US 14 perro 170",
				"US 15 jauja 160",
				"US 16 listo 160",
				"XE 20 listo 230"
		};
		
		ITuple[] tuples = new ITuple[inputElements.length];
		Schema schema = Schema.parse("country:string,age:vint,name:string,height:int");
		int i=0; 
		for (String inputElement : inputElements){
			withInput("input",writable(inputElement));
			tuples[i++]=createTuple(inputElement, schema);
		}
		
		Grouper grouper = new Grouper(getConf());
		grouper.setOutputFormat(SequenceFileOutputFormat.class);
		grouper.setOutputHandler(IdentityRed.class);
		
		grouper.setSchema(schema);
		SortCriteria sortCriteria = SortCriteria.parse("country ASC,age ASC,name ASC");
		grouper.setSortCriteria(sortCriteria);
		grouper.setRollupBaseFieldsToGroupBy("country");
		grouper.setFieldsToGroupBy("country","age","name");
		
		grouper.setOutputKeyClass(Text.class);
		grouper.setOutputValueClass(Text.class);
		grouper.addInput(new Path("input"), SequenceFileInputFormat.class, Mapy.class);
		Path outputPath = new Path("output");
		grouper.setOutputPath(outputPath);
		Job job = grouper.createJob();
		job.setNumReduceTasks(1);

		
		assertRun(job);
		
		FileSystem fs = FileSystem.get(getConf());
		Path output = new Path(outputPath + "/part-r-00000");
		checkGrouperWithRollupOutput(output,0,2);
		SequenceFile.Reader reader = new SequenceFile.Reader(fs,output,getConf());
		
		assertOutput(reader,"OPEN 0",tuples[0]);
		assertOutput(reader,"OPEN 1",tuples[0]);
		assertOutput(reader,"OPEN 2",tuples[0]);
		assertOutput(reader,"ELEMENT",tuples[0]);
		assertOutput(reader,"CLOSE 2",tuples[0]);
		assertOutput(reader,"CLOSE 1",tuples[0]);
		assertOutput(reader,"CLOSE 0",tuples[0]);
		
		assertOutput(reader,"OPEN 0",tuples[1]);
		assertOutput(reader,"OPEN 1",tuples[1]);
		assertOutput(reader,"OPEN 2",tuples[1]);
		assertOutput(reader,"ELEMENT",tuples[1]);
		assertOutput(reader,"CLOSE 2",tuples[1]);
		
		assertOutput(reader,"OPEN 2",tuples[2]);
		assertOutput(reader,"ELEMENT",tuples[2]);
		assertOutput(reader,"ELEMENT",tuples[3]);
		assertOutput(reader,"CLOSE 2",tuples[3]);
		assertOutput(reader,"CLOSE 1",tuples[3]);
		
		assertOutput(reader,"OPEN 1",tuples[4]);
		assertOutput(reader,"OPEN 2",tuples[4]);
		assertOutput(reader,"ELEMENT",tuples[4]);
		assertOutput(reader,"CLOSE 2",tuples[4]);
		assertOutput(reader,"CLOSE 1",tuples[4]);
		
		assertOutput(reader,"OPEN 1",tuples[5]);
		assertOutput(reader,"OPEN 2",tuples[5]);
		assertOutput(reader,"ELEMENT",tuples[5]);
		assertOutput(reader,"CLOSE 2",tuples[5]);
		assertOutput(reader,"CLOSE 1",tuples[5]);
		assertOutput(reader,"CLOSE 0",tuples[5]);
		
		assertOutput(reader,"OPEN 0",tuples[6]);
		assertOutput(reader,"OPEN 1",tuples[6]);
		assertOutput(reader,"OPEN 2",tuples[6]);
		assertOutput(reader,"ELEMENT",tuples[6]);
		assertOutput(reader,"CLOSE 2",tuples[6]);
		assertOutput(reader,"CLOSE 1",tuples[6]);
		assertOutput(reader,"CLOSE 0",tuples[6]);
		
	}
	
	private enum State{
		OPEN,CLOSE,ELEMENT
	}
	
	
	/**
	 * 
	 * Checks that {@link Grouper} calls properly {@link GroupHandler#onOpenGroup}, 
	 * {@link GroupHandler#onCloseGroup} and {@link GroupHandler#onGroupElements} and checks that the elements (tuples) passed are coherent. 
	 * This method assumes an specific output from the {@link GroupHandler}. The output needs to be a Text,Text for key and value
	 * This will be the format used : 
	 * key("OPEN depth"), value("serialized value")
	 * key("CLOSE depth"), value("serialized value")
	 * key("ELEMENT"),value("serialized element")   (for every element received in onElements needs to contain a record like this)
	 * 
	 * For instance : 
	 * key("OPEN 0"), value(" element1")
	 * key("OPEN 1"), value("element1 ")
	 * key("ELEMENT") , value ("element1")
	 * key("ELEMENT"),value ("element2")
	 * key("CLOSE 1"),value ("element2")
	 * key("CLOSE 0"),value("element2")
	 * 
	 * 
	 */
	public void checkGrouperWithRollupOutput(Path path,int minDepth,int maxDepth) throws IOException{
		SequenceFile.Reader reader = new SequenceFile.Reader(FileSystem.getLocal(getConf()),path,getConf());
		
		Text actualKey=new Text();
		Text actualValue = new Text();
		reader.next(actualKey,actualValue); //first action
		String currentKey = actualKey.toString();
		String currentValue = actualValue.toString();
		
		Assert.assertTrue("First output needs to be an OPEN ",currentKey.startsWith("OPEN"));
		int currentDepth = Integer.parseInt(currentKey.split(" ")[1]);
		Assert.assertEquals("First OPEN needs to match minDepth",minDepth,currentDepth);
		int lastDepth = currentDepth;
		String lastValue = currentValue;
		State lastState = State.OPEN;
		
		while (reader.next(actualKey, actualValue)){
			currentKey = actualKey.toString();
			currentValue = actualValue.toString();
			if (currentKey.startsWith("OPEN")){
				currentDepth = Integer.parseInt(currentKey.split(" ")[1]);
				Assert.assertEquals("OPEN needs to increase depth in +1 ",lastDepth +1,currentDepth);
				Assert.assertTrue("Too many OPENs, over maxDepth ",maxDepth >= currentDepth);
				if (lastState == State.OPEN){
					Assert.assertEquals("First element in OPEN needs to match first element in previous OPEN",lastValue, currentValue);
				} else if (lastState == State.CLOSE){
					Assert.assertNotSame("Element from new group needs to be different from last element from last group ",lastValue, currentValue);
				} else {
					Assert.fail("Not allowed OPEN after ELEMENT");
				}
				lastState = State.OPEN;
				lastValue = currentValue;
				lastDepth = currentDepth;
				
			} else if (currentKey.startsWith("CLOSE")){
				currentDepth = Integer.parseInt(currentKey.split(" ")[1]);
				Assert.assertNotSame("Not allowed CLOSE after OPEN , needs at least one ELEMENT in between",State.OPEN,lastState);
				Assert.assertEquals("CLOSE depth needs to match previous OPEN depth",lastDepth,currentDepth);
				Assert.assertEquals("Element in CLOSE needs to match lastElement in group",lastValue, currentValue);
				
				lastState = State.CLOSE;
				lastValue = currentValue;
				lastDepth = currentDepth-1;
			
			} else if (currentKey.startsWith("ELEMENT")){
				Assert.assertNotSame("Not allowed ELEMENT after CLOSE, needs an OPEN or ELEMENT before",State.CLOSE,lastState);
				lastState = State.ELEMENT;
				lastValue = currentValue;
			}
		}
		
		Assert.assertEquals("File doesn't properly finishes with a CLOSE ",State.CLOSE, lastState);
		Assert.assertEquals("Last CLOSE doesn't close the minDepth ",minDepth-1, lastDepth);
		reader.close();
	}
	
	
	
	private void assertOutput(SequenceFile.Reader reader,String expectedKey,ITuple expectedValue) throws IOException{
		Text actualKey=new Text();
		Text actualValue = new Text();
		reader.next(actualKey, actualValue);
		
		Assert.assertEquals(new Text(expectedKey),actualKey);
		Assert.assertEquals(new Text(expectedValue.toString()),actualValue);
	}
	
	
}