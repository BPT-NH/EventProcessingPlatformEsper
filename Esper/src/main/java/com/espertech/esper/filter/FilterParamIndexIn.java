/**************************************************************************************
 * Copyright (C) 2008 EsperTech, Inc. All rights reserved.                            *
 * http://esper.codehaus.org                                                          *
 * http://www.espertech.com                                                           *
 * ---------------------------------------------------------------------------------- *
 * The software in this package is published under the terms of the GPL license       *
 * a copy of which has been included with this distribution in the license.txt file.  *
 **************************************************************************************/
package com.espertech.esper.filter;

import com.espertech.esper.client.EventBean;
import com.espertech.esper.client.EventPropertyGetter;
import com.espertech.esper.collection.MultiKeyUntyped;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.util.*;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Index for filter parameter constants to match using the 'in' operator to match against a supplied set of values
 * (i.e. multiple possible exact matches).
 * The implementation is based on a regular HashMap.
 */
public final class FilterParamIndexIn extends FilterParamIndexLookupableBase
{
    private final Map<Object, List<EventEvaluator>> constantsMap;
    private final Map<MultiKeyUntyped, EventEvaluator> evaluatorsMap;
    private final ReadWriteLock constantsMapRWLock;

    public FilterParamIndexIn(FilterSpecLookupable lookupable) {
        super(FilterOperator.IN_LIST_OF_VALUES, lookupable);

        constantsMap = new HashMap<Object, List<EventEvaluator>>();
        evaluatorsMap = new HashMap<MultiKeyUntyped, EventEvaluator>();
        constantsMapRWLock = new ReentrantReadWriteLock();
    }

    public final EventEvaluator get(Object filterConstant)
    {
        MultiKeyUntyped keyValues = (MultiKeyUntyped) filterConstant;
        return evaluatorsMap.get(keyValues);
    }

    public final void put(Object filterConstant, EventEvaluator evaluator)
    {
        // Store evaluator keyed to set of values
        MultiKeyUntyped keys = (MultiKeyUntyped) filterConstant;

        // make sure to remove the old evaluator for this constant
        EventEvaluator oldEvaluator = evaluatorsMap.put(keys, evaluator);

        // Store each value to match against in Map with it's evaluator as a list
        Object[] keyValues = keys.getKeys();
        for (int i = 0; i < keyValues.length; i++)
        {
            List<EventEvaluator> evaluators = constantsMap.get(keyValues[i]);
            if (evaluators == null)
            {
                evaluators = new LinkedList<EventEvaluator>();
                constantsMap.put(keyValues[i], evaluators);
            }
            else
            {
                if (oldEvaluator != null)
                {
                    evaluators.remove(oldEvaluator);
                }
            }
            evaluators.add(evaluator);
        }
    }

    public final boolean remove(Object filterConstant)
    {
        MultiKeyUntyped keys = (MultiKeyUntyped) filterConstant;

        // remove the mapping of value set to evaluator
        EventEvaluator eval = evaluatorsMap.remove(keys);
        boolean isRemoved = false;
        if (eval != null)
        {
            isRemoved = true;
        }

        Object[] keyValues = keys.getKeys();
        for (int i = 0; i < keyValues.length; i++)
        {
            List<EventEvaluator> evaluators = constantsMap.get(keyValues[i]);
            if (evaluators != null) // could be removed already as same-value constants existed
            {
                evaluators.remove(eval);
                if (evaluators.isEmpty())
                {
                    constantsMap.remove(keyValues[i]);
                }
            }
        }
        return isRemoved;
    }

    public final int size()
    {
        return constantsMap.size();
    }

    public final ReadWriteLock getReadWriteLock()
    {
        return constantsMapRWLock;
    }

    public final void matchEvent(EventBean theEvent, Collection<FilterHandle> matches)
    {
        Object attributeValue = lookupable.getGetter().get(theEvent);

        if (attributeValue == null)
        {
            return;
        }

        // Look up in hashtable
        constantsMapRWLock.readLock().lock();
        List<EventEvaluator> evaluators = constantsMap.get(attributeValue);

        // No listener found for the value, return
        if (evaluators == null)
        {
            constantsMapRWLock.readLock().unlock();
            return;
        }

        try {
            for (EventEvaluator evaluator : evaluators)
            {
                evaluator.matchEvent(theEvent, matches);
            }
        }
        finally {
            constantsMapRWLock.readLock().unlock();
        }
    }

    private static final Log log = LogFactory.getLog(FilterParamIndexIn.class);
}
