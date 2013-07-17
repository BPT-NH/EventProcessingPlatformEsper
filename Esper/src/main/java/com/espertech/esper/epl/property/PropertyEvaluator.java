/*
 * *************************************************************************************
 *  Copyright (C) 2008 EsperTech, Inc. All rights reserved.                            *
 *  http://esper.codehaus.org                                                          *
 *  http://www.espertech.com                                                           *
 *  ---------------------------------------------------------------------------------- *
 *  The software in this package is published under the terms of the GPL license       *
 *  a copy of which has been included with this distribution in the license.txt file.  *
 * *************************************************************************************
 */

package com.espertech.esper.epl.property;

import com.espertech.esper.client.EventBean;
import com.espertech.esper.client.EventType;
import com.espertech.esper.epl.expression.ExprEvaluatorContext;

/**
 * Interface for a function that evaluates the properties of an event and returns event representing the properties.
 */
public interface PropertyEvaluator
{
    /**
     * Returns the result events based on property values, or null if none found.
     * @param theEvent to inspect
     * @param exprEvaluatorContext expression evaluation context
     * @return events representing property(s)
     */
    public EventBean[] getProperty(EventBean theEvent, ExprEvaluatorContext exprEvaluatorContext);

    /**
     * Returns the result type of the events generated by evaluating a property expression.
     * @return result event type
     */
    public EventType getFragmentEventType();

    /**
     * Compare to another property evaluator.
     * @param otherFilterPropertyEval other
     * @return equals or not 
     */
    public boolean compareTo(PropertyEvaluator otherFilterPropertyEval);
}
