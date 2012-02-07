package com.datasalt.pangool;

import java.util.HashMap;
import java.util.Map;

import org.apache.hadoop.io.RawComparator;

import com.datasalt.pangool.io.tuple.ITuple.InvalidFieldException;
import com.datasalt.pangool.SortCriteria.SortOrder;

/**
 * Builds an inmutable {@link Sorting} instance.
 * May have childs {@link SortCriteriaBuilder}.
 * 
 * @author pere
 * 
 */
@SuppressWarnings("rawtypes")
public class SortingBuilder extends SortCriteriaBuilder {

	private Map<Integer, SortCriteriaBuilder> secondarySortBuilders;
	boolean sourceIdFieldAdded = false;
	
	public static SortingBuilder newOne() {
		return new SortingBuilder();
	}
	
	public SortingBuilder() {
		super(null);
		secondarySortBuilders = new HashMap<Integer, SortCriteriaBuilder>();
	}
	
	public SortingBuilder add(String fieldName, SortOrder order, Class<? extends RawComparator> customComparator) throws InvalidFieldException {
		super.add(fieldName, order, customComparator);
		return this;
	}
	
	public SortingBuilder add(String fieldName) throws InvalidFieldException {
		super.add(fieldName, SortOrder.ASC);
		return this;
	}
	
	@Override
	public SortingBuilder add(String fieldName, SortOrder order) throws InvalidFieldException {
		super.add(fieldName, order);
	  return this;
	}
	
	public SortCriteriaBuilder secondarySort(Integer sourceId) {
		SortCriteriaBuilder builder = new SortCriteriaBuilder(this);
		secondarySortBuilders.put(sourceId, builder);
		return builder;
	}

	public Sorting buildSorting() throws CoGrouperException {
		Map<Integer, SortCriteria> secondarySortCriterias = new HashMap<Integer, SortCriteria>();
		for(Map.Entry<Integer, SortCriteriaBuilder> builders: secondarySortBuilders.entrySet()) {
			secondarySortCriterias.put(builders.getKey(), builders.getValue().buildSortCriteria());
		}
		// Check that we have #source# field given that we have particular sortings
		if(!secondarySortCriterias.isEmpty() && !sourceIdFieldAdded) {
			throw new CoGrouperException("SourceId field must be added if specific sort criterias have been added.");
		}
		Sorting sorting = new Sorting(buildSortCriteria(), secondarySortCriterias);
		return sorting;
	}
}
