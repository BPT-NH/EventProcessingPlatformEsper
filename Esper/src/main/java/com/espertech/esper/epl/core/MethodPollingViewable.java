/**************************************************************************************
 * Copyright (C) 2008 EsperTech, Inc. All rights reserved.                            *
 * http://esper.codehaus.org                                                          *
 * http://www.espertech.com                                                           *
 * ---------------------------------------------------------------------------------- *
 * The software in this package is published under the terms of the GPL license       *
 * a copy of which has been included with this distribution in the license.txt file.  *
 **************************************************************************************/
package com.espertech.esper.epl.core;

import com.espertech.esper.client.ConfigurationInformation;
import com.espertech.esper.client.EPException;
import com.espertech.esper.client.EventBean;
import com.espertech.esper.client.EventType;
import com.espertech.esper.collection.IterablesArrayIterator;
import com.espertech.esper.collection.Pair;
import com.espertech.esper.epl.db.DataCache;
import com.espertech.esper.epl.db.PollExecStrategy;
import com.espertech.esper.epl.expression.*;
import com.espertech.esper.epl.join.pollindex.PollResultIndexingStrategy;
import com.espertech.esper.epl.join.table.EventTable;
import com.espertech.esper.epl.join.table.UnindexedEventTableList;
import com.espertech.esper.epl.spec.MethodStreamSpec;
import com.espertech.esper.epl.variable.VariableService;
import com.espertech.esper.event.EventAdapterService;
import com.espertech.esper.schedule.SchedulingService;
import com.espertech.esper.schedule.TimeProvider;
import com.espertech.esper.util.JavaClassHelper;
import com.espertech.esper.view.HistoricalEventViewable;
import com.espertech.esper.view.View;
import com.espertech.esper.view.ViewSupport;

import java.lang.annotation.Annotation;
import java.util.*;

/**
 * Polling-data provider that calls a static method on a class and passed parameters, and wraps the
 * results as POJO events.
 */
public class MethodPollingViewable implements HistoricalEventViewable
{
    private final MethodStreamSpec methodStreamSpec;
    private final PollExecStrategy pollExecStrategy;
    private final List<ExprNode> inputParameters;
    private final DataCache dataCache;
    private final EventType eventType;
    private final ThreadLocal<DataCache> dataCacheThreadLocal = new ThreadLocal<DataCache>();
    private final ExprEvaluatorContext exprEvaluatorContext;

    private SortedSet<Integer> requiredStreams;
    private ExprEvaluator[] validatedExprNodes;

    private static final EventBean[][] NULL_ROWS;
    static {
        NULL_ROWS = new EventBean[1][];
        NULL_ROWS[0] = new EventBean[1];
    }
    private static final PollResultIndexingStrategy iteratorIndexingStrategy = new PollResultIndexingStrategy()
    {
        public EventTable index(List<EventBean> pollResult, boolean isActiveCache)
        {
            return new UnindexedEventTableList(pollResult);
        }

        public String toQueryPlan() {
            return this.getClass().getSimpleName() + " unindexed";
        }
    };

    /**
     * Ctor.
     * @param methodStreamSpec defines class and method names
     * @param myStreamNumber is the stream number
     * @param inputParameters the input parameter expressions
     * @param pollExecStrategy the execution strategy
     * @param dataCache the cache to use
     * @param eventType the type of event returned
     * @param exprEvaluatorContext expression evaluation context
     */
    public MethodPollingViewable(
                           MethodStreamSpec methodStreamSpec,
                           int myStreamNumber,
                           List<ExprNode> inputParameters,
                           PollExecStrategy pollExecStrategy,
                           DataCache dataCache,
                           EventType eventType,
                           ExprEvaluatorContext exprEvaluatorContext)
    {
        this.methodStreamSpec = methodStreamSpec;
        this.inputParameters = inputParameters;
        this.pollExecStrategy = pollExecStrategy;
        this.dataCache = dataCache;
        this.eventType = eventType;
        this.exprEvaluatorContext = exprEvaluatorContext;
    }

    public void stop()
    {
        pollExecStrategy.destroy();
    }

    public ThreadLocal<DataCache> getDataCacheThreadLocal()
    {
        return dataCacheThreadLocal;
    }

    public void validate(EngineImportService engineImportService, StreamTypeService streamTypeService, MethodResolutionService methodResolutionService, TimeProvider timeProvider,
                         VariableService variableService, ExprEvaluatorContext exprEvaluatorContext, ConfigurationInformation configSnapshot,
                         SchedulingService schedulingService, String engineURI, Map<Integer, List<ExprNode>> sqlParameters, EventAdapterService eventAdapterService, String statementName, String statementId, Annotation[] annotations) throws ExprValidationException {

        // validate and visit
        ExprValidationContext validationContext = new ExprValidationContext(streamTypeService, methodResolutionService, null, timeProvider, variableService, exprEvaluatorContext, eventAdapterService, statementName, statementId, annotations, null);
        ExprNodeIdentifierVisitor visitor = new ExprNodeIdentifierVisitor(true);
        final List<ExprNode> validatedInputParameters = new ArrayList<ExprNode>();
        for (ExprNode exprNode : inputParameters) {
            ExprNode validated = ExprNodeUtility.getValidatedSubtree(exprNode, validationContext);
            validatedInputParameters.add(validated);
            validated.accept(visitor);
        }

        // determine required streams
        requiredStreams = new TreeSet<Integer>();
        for (Pair<Integer, String> identifier : visitor.getExprProperties())
        {
            requiredStreams.add(identifier.getFirst());
        }

        ExprNodeUtilResolveExceptionHandler handler = new ExprNodeUtilResolveExceptionHandler() {
            public ExprValidationException handle(Exception e) {
                if (inputParameters.size() == 0)
                {
                    return new ExprValidationException("Method footprint does not match the number or type of expression parameters, expecting no parameters in method: " + e.getMessage());
                }
                Class[] resultTypes = ExprNodeUtility.getExprResultTypes(validatedInputParameters);
                return new ExprValidationException("Method footprint does not match the number or type of expression parameters, expecting a method where parameters are typed '" +
                        JavaClassHelper.getParameterAsString(resultTypes) + "': " + e.getMessage());
            }
        };

        ExprNodeUtilMethodDesc desc = ExprNodeUtility.resolveMethodAllowWildcardAndStream(methodStreamSpec.getClassName(), null,
                methodStreamSpec.getMethodName(), validatedInputParameters, methodResolutionService, eventAdapterService, statementId,
                false, null, handler, methodStreamSpec.getMethodName());
        validatedExprNodes = desc.getChildEvals();
    }

    public EventTable[] poll(EventBean[][] lookupEventsPerStream, PollResultIndexingStrategy indexingStrategy, ExprEvaluatorContext exprEvaluatorContext)
    {
        DataCache localDataCache = dataCacheThreadLocal.get();
        boolean strategyStarted = false;

        EventTable[] resultPerInputRow = new EventTable[lookupEventsPerStream.length];

        // Get input parameters for each row
        for (int row = 0; row < lookupEventsPerStream.length; row++)
        {
            Object[] lookupValues = new Object[inputParameters.size()];

            // Build lookup keys
            for (int valueNum = 0; valueNum < inputParameters.size(); valueNum++)
            {
                Object parameterValue = validatedExprNodes[valueNum].evaluate(lookupEventsPerStream[row], true, exprEvaluatorContext);
                lookupValues[valueNum] = parameterValue;
            }

            EventTable result = null;

            // try the threadlocal iteration cache, if set
            if (localDataCache != null)
            {
                result = localDataCache.getCached(lookupValues);
            }

            // try the connection cache
            if (result == null)
            {
                result = dataCache.getCached(lookupValues);
                if ((result != null) && (localDataCache != null))
                {
                    localDataCache.put(lookupValues, result);
                }
            }

            if (result != null)     // found in cache
            {
                resultPerInputRow[row] = result;
            }
            else        // not found in cache, get from actual polling (db query)
            {
                try
                {
                    if (!strategyStarted)
                    {
                        pollExecStrategy.start();
                        strategyStarted = true;
                    }

                    // Poll using the polling execution strategy and lookup values
                    List<EventBean> pollResult = pollExecStrategy.poll(lookupValues);

                    // index the result, if required, using an indexing strategy
                    EventTable indexTable = indexingStrategy.index(pollResult, dataCache.isActive());

                    // assign to row
                    resultPerInputRow[row] = indexTable;

                    // save in cache
                    dataCache.put(lookupValues, indexTable);

                    if (localDataCache != null)
                    {
                        localDataCache.put(lookupValues, indexTable);
                    }
                }
                catch (EPException ex)
                {
                    if (strategyStarted)
                    {
                        pollExecStrategy.done();
                    }
                    throw ex;
                }
            }
        }

        if (strategyStarted)
        {
            pollExecStrategy.done();
        }

        return resultPerInputRow;
    }

    public View addView(View view)
    {
        view.setParent(this);
        return view;
    }

    public View[] getViews()
    {
        return ViewSupport.EMPTY_VIEW_ARRAY;
    }

    public boolean removeView(View view)
    {
        throw new UnsupportedOperationException("Subviews not supported");
    }

    public void removeAllViews()
    {
        throw new UnsupportedOperationException("Subviews not supported");
    }

    public boolean hasViews()
    {
        return false;
    }

    public EventType getEventType()
    {
        return eventType;
    }

    public Iterator<EventBean> iterator()
    {
        EventTable[] result = poll(NULL_ROWS, iteratorIndexingStrategy, exprEvaluatorContext);
        return new IterablesArrayIterator(result);
    }

    public SortedSet<Integer> getRequiredStreams()
    {
        return requiredStreams;
    }

    public boolean hasRequiredStreams()
    {
        return !requiredStreams.isEmpty();
    }
}
