/**************************************************************************************
 * Copyright (C) 2008 EsperTech, Inc. All rights reserved.                            *
 * http://esper.codehaus.org                                                          *
 * http://www.espertech.com                                                           *
 * ---------------------------------------------------------------------------------- *
 * The software in this package is published under the terms of the GPL license       *
 * a copy of which has been included with this distribution in the license.txt file.  *
 **************************************************************************************/
package com.espertech.esper.epl.expression;

import com.espertech.esper.client.EventBean;
import com.espertech.esper.client.EventType;
import com.espertech.esper.epl.agg.access.AggregationStateType;
import com.espertech.esper.epl.agg.service.AggregationMethodFactory;
import com.espertech.esper.epl.core.StreamTypeService;
import com.espertech.esper.event.EventAdapterService;

import java.io.StringWriter;
import java.util.*;

public class ExprAggMultiFunctionLinearAccessNode extends ExprAggregateNodeBase implements ExprEvaluatorEnumeration
{
    private static final long serialVersionUID = -6088874732989061687L;

    private final AggregationStateType stateType;
    private final boolean isWildcard;
    private final String streamWildcard;
    private transient EventType containedType;
    private transient ScalarCollectionEvaluator scalarCollectionEvaluator;

    /**
     * Ctor.
     */
    public ExprAggMultiFunctionLinearAccessNode(AggregationStateType stateType, boolean wildcard, String streamWildcard)
    {
        super(false);
        this.stateType = stateType;
        this.isWildcard = wildcard;
        this.streamWildcard = streamWildcard;
    }

    public AggregationMethodFactory validateAggregationChild(ExprValidationContext validationContext) throws ExprValidationException
    {
        StreamTypeService streamTypeService = validationContext.getStreamTypeService();
        int streamNum;
        Class resultType;
        ExprEvaluator evaluator;
        ExprNode evaluatorIndex = null;
        boolean istreamOnly;

        if (isWildcard) {
            if (streamTypeService.getStreamNames().length > 1) {
                throw new ExprValidationException(getErrorPrefix() + " requires that in joins or subqueries the stream-wildcard (stream-alias.*) syntax is used instead");
            }
            streamNum = 0;
            if (streamTypeService.getStreamNames().length == 0) {    // could be the case for
                throw new ExprValidationException(getErrorPrefix() + " requires that at least one stream is provided");
            }
            containedType = streamTypeService.getEventTypes()[0];
            resultType = streamTypeService.getEventTypes()[0].getUnderlyingType();
            final Class returnType = resultType;
            evaluator = new ExprEvaluator() {
                public Object evaluate(EventBean[] eventsPerStream, boolean isNewData, ExprEvaluatorContext context)
                {
                    if ((eventsPerStream == null) || (eventsPerStream[0] == null)) {
                        return null;
                    }
                    return eventsPerStream[0].getUnderlying();
                }
                public Class getType()
                {
                    return returnType;
                }
                public Map<String, Object> getEventType() {
                    return null;
                }
            };
            istreamOnly = getIstreamOnly(streamTypeService, 0);
            if ((stateType == AggregationStateType.WINDOW) && istreamOnly && !streamTypeService.isOnDemandStreams()) {
                throw new ExprValidationException(getErrorPrefix() + " requires that the aggregated events provide a remove stream; Defined a data window onto the stream or use 'firstever', 'lastever' or 'nth' instead");
            }
            this.addChildNodeToFront(new ExprStreamUnderlyingNodeImpl(null, true, streamNum, resultType));
        }
        else if (streamWildcard != null) {
            streamNum = streamTypeService.getStreamNumForStreamName(streamWildcard);
            if (streamNum == -1) {
                throw new ExprValidationException(getErrorPrefix() + " stream wildcard '" + streamWildcard + "' does not resolve to any stream");
            }
            istreamOnly = getIstreamOnly(streamTypeService, streamNum);
            if ((stateType == AggregationStateType.WINDOW) && istreamOnly && !streamTypeService.isOnDemandStreams()) {
                throw new ExprValidationException(getErrorPrefix() + " requires that the aggregated events provide a remove stream; Defined a data window onto the stream or use 'firstever', 'lastever' or 'nth' instead");
            }
            EventType type = streamTypeService.getEventTypes()[streamNum];
            containedType = type;
            resultType = type.getUnderlyingType();
            final int streamNumUsed = streamNum;
            final Class returnType = resultType;
            evaluator = new ExprEvaluator() {
                public Object evaluate(EventBean[] eventsPerStream, boolean isNewData, ExprEvaluatorContext context)
                {
                    if ((eventsPerStream == null) || (eventsPerStream[streamNumUsed] == null)) {
                        return null;
                    }
                    return eventsPerStream[streamNumUsed].getUnderlying();
                }

                public Class getType()
                {
                    return returnType;
                }
                public Map<String, Object> getEventType() {
                    return null;
                }
            };
            this.addChildNodeToFront(new ExprStreamUnderlyingNodeImpl(streamWildcard, false, streamNum, resultType));
        }
        else {
            if (this.getChildNodes().length == 0) {
                throw new ExprValidationException(getErrorPrefix() + " requires a expression or wildcard (*) or stream wildcard (stream-alias.*)");
            }
            ExprNode child = this.getChildNodes()[0];
            Set<Integer> streams = ExprNodeUtility.getIdentStreamNumbers(child);
            if ((streams.isEmpty() || (streams.size() > 1))) {
                throw new ExprValidationException(getErrorPrefix() + " requires that any child expressions evaluate properties of the same stream; Use 'firstever' or 'lastever' or 'nth' instead");
            }
            streamNum = streams.iterator().next();
            istreamOnly = getIstreamOnly(streamTypeService, streamNum);
            if ((stateType == AggregationStateType.WINDOW) && istreamOnly && !streamTypeService.isOnDemandStreams()) {
                throw new ExprValidationException(getErrorPrefix() + " requires that the aggregated events provide a remove stream; Defined a data window onto the stream or use 'firstever', 'lastever' or 'nth' instead");
            }
            resultType = this.getChildNodes()[0].getExprEvaluator().getType();
            evaluator = this.getChildNodes()[0].getExprEvaluator();
            scalarCollectionEvaluator = new ScalarCollectionEvaluator(evaluator, streamNum, resultType);
        }

        if (this.getChildNodes().length > 1) {
            if (stateType == AggregationStateType.WINDOW) {
                throw new ExprValidationException(getErrorPrefix() + " does not accept an index expression; Use 'first' or 'last' instead");
            }
            evaluatorIndex = this.getChildNodes()[1];
            if (evaluatorIndex.getExprEvaluator().getType() != Integer.class) {
                throw new ExprValidationException(getErrorPrefix() + " requires an index expression that returns an integer value");
            }
        }

        return new ExprAggMultiFunctionLinearAccessNodeFactory(stateType, resultType, streamNum, evaluator, evaluatorIndex, istreamOnly, streamTypeService.isOnDemandStreams());
    }

    protected static boolean getIstreamOnly(StreamTypeService streamTypeService, int streamNum) {
        if (streamNum < streamTypeService.getEventTypes().length) {
            return streamTypeService.getIStreamOnly()[streamNum];
        }
        // this could happen for match-recognize which has different stream types for selection and for aggregation
        return streamTypeService.getIStreamOnly()[0];
    }

    @Override
    protected String getAggregationFunctionName() {
        return stateType.toString().toLowerCase();
    }

    public String toExpressionString()
    {
        StringWriter writer = new StringWriter();
        writer.append(stateType.toString().toLowerCase());
        ExprNodeUtility.toExpressionStringParams(writer, this.getChildNodes(), isWildcard, streamWildcard, true);
        return writer.toString();
    }

    public AggregationStateType getStateType()
    {
        return stateType;
    }

    public boolean isWildcard()
    {
        return isWildcard;
    }

    public String getStreamWildcard()
    {
        return streamWildcard;
    }

    public Collection<EventBean> evaluateGetROCollectionEvents(EventBean[] eventsPerStream, boolean isNewData, ExprEvaluatorContext context) {
        return super.aggregationResultFuture.getCollection(column, context);
    }

    public Collection evaluateGetROCollectionScalar(EventBean[] eventsPerStream, boolean isNewData, ExprEvaluatorContext context) {
        Collection<EventBean> events = evaluateGetROCollectionEvents(eventsPerStream, isNewData, context);
        if (events == null) {
            return null;
        }
        if (events.isEmpty()) {
            return Collections.emptyList();
        }

        Deque list = new ArrayDeque();
        for (EventBean bean : events) {
            list.add(scalarCollectionEvaluator.evaluate(bean, isNewData, context));
        }
        return list;
    }

    public EventType getEventTypeCollection(EventAdapterService eventAdapterService) {
        if (stateType == AggregationStateType.FIRST || stateType == AggregationStateType.LAST) {
            return null;
        }
        return containedType;
    }

    public Class getComponentTypeCollection() throws ExprValidationException {
        return scalarCollectionEvaluator == null ? null : scalarCollectionEvaluator.componentType;
    }

    public EventType getEventTypeSingle(EventAdapterService eventAdapterService, String statementId) throws ExprValidationException {
        if (stateType == AggregationStateType.FIRST || stateType == AggregationStateType.LAST) {
            return containedType;
        }
        return null;
    }

    public EventBean evaluateGetEventBean(EventBean[] eventsPerStream, boolean isNewData, ExprEvaluatorContext context) {
        return super.aggregationResultFuture.getEventBean(column, context);
    }

    @Override
    protected boolean equalsNodeAggregate(ExprAggregateNode node) {
        if (this == node) return true;
        if (node == null || getClass() != node.getClass()) return false;

        ExprAggMultiFunctionLinearAccessNode that = (ExprAggMultiFunctionLinearAccessNode) node;

        if (isWildcard != that.isWildcard) return false;
        if (stateType != that.stateType) return false;
        if (streamWildcard != null ? !streamWildcard.equals(that.streamWildcard) : that.streamWildcard != null)
            return false;

        return true;
    }

    private String getErrorPrefix() {
        return "The '" + stateType.toString().toLowerCase() + "' aggregation function";
    }

    private static class ScalarCollectionEvaluator {
        private final ExprEvaluator scalarEvaluator;
        private final int streamId;
        private final Class componentType;
        private final EventBean[] eventsPerStream;

        private ScalarCollectionEvaluator(ExprEvaluator scalarEvaluator, int streamId, Class componentType) {
            this.scalarEvaluator = scalarEvaluator;
            this.streamId = streamId;
            this.componentType = componentType;
            this.eventsPerStream = new EventBean[streamId + 1];
        }

        public Object evaluate(EventBean theEvent, boolean isNewData, ExprEvaluatorContext context) {
            eventsPerStream[streamId] = theEvent;
            return scalarEvaluator.evaluate(eventsPerStream, isNewData, context);
        }

        public Class getComponentType() {
            return componentType;
        }
    }
}