/*
 * (c) Copyright 2004, 2005, 2006, 2007 Hewlett-Packard Development Company, LP
 * [See end of file]
 */

package com.hp.hpl.jena.sparql.engine.engine1;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.hp.hpl.jena.query.Dataset;
import com.hp.hpl.jena.query.Query;
import com.hp.hpl.jena.query.QueryExecution;
import com.hp.hpl.jena.query.QuerySolution;

import com.hp.hpl.jena.sparql.ARQConstants;
import com.hp.hpl.jena.sparql.ARQInternalErrorException;
import com.hp.hpl.jena.sparql.core.DatasetGraph;
import com.hp.hpl.jena.sparql.core.Var;
import com.hp.hpl.jena.sparql.engine.*;
import com.hp.hpl.jena.sparql.engine.binding.Binding;
import com.hp.hpl.jena.sparql.engine.binding.BindingMap;
import com.hp.hpl.jena.sparql.engine.binding.BindingRoot;
import com.hp.hpl.jena.sparql.engine.binding.BindingUtils;
import com.hp.hpl.jena.sparql.engine.engine1.compiler.QueryPatternCompiler;
import com.hp.hpl.jena.sparql.engine.engine1.plan.PlanDistinct;
import com.hp.hpl.jena.sparql.engine.engine1.plan.PlanLimitOffset;
import com.hp.hpl.jena.sparql.engine.engine1.plan.PlanOrderBy;
import com.hp.hpl.jena.sparql.engine.engine1.plan.PlanProject;
import com.hp.hpl.jena.sparql.engine.iterator.QueryIterNullIterator;
import com.hp.hpl.jena.sparql.engine.iterator.QueryIterSingleton;
import com.hp.hpl.jena.sparql.engine.iterator.QueryIteratorCheck;
import com.hp.hpl.jena.sparql.syntax.Element;
import com.hp.hpl.jena.sparql.util.Context;

/**
 * @author     Andy Seaborne
 * @version    $Id: QueryEngine.java,v 1.95 2007/02/08 16:19:00 andy_seaborne Exp $
 */
 
public class QueryEngine1 extends QueryEngineBase
{
    private static Log log = LogFactory.getLog(QueryEngine1.class) ;
    
    static public QueryEngineFactory getFactory() { return factory ; } 
    static public void register()       { QueryEngineRegistry.addFactory(factory) ; }
    static public void unregister()     { QueryEngineRegistry.removeFactory(factory) ; }
    
    private PlanElement plan = null ;           // The whole query
    private PlanElement planPattern = null ;    // Just the pattern (convenient).
    private QuerySolution startSolution ;
    
    /** Create a QueryEngine.  The preferred mechanism is through QueryEngineFactory */
    
    public QueryEngine1(Query q)
    {
        this(q, null) ;
    }

    public QueryEngine1(Query q, Context context)
    {
        super(q, context) ;
    }

    // ---- Plan-ness operations
    
    /** Get the Plan for the whole query (building it if it has not already been built)
     * 
     * @return Plan
     */
    public PlanElement getPlanElement() 
    {
        if ( plan == null )
            plan = buildPlan(getModifiers(), query.getQueryPattern()) ;
        return plan ;
    }
    
    /** Get the PlanElement for the start of the query pattern.
     *  Builds the whole plan if necessary.
     * 
     * @return PlanElement
     */
    public PlanElement getPlanPattern() 
    {
        if ( plan == null )
            getPlanElement() ;
        return planPattern ;    
    }
    


    // ---- Interface to QueryEngineBase
    
    protected final
    Plan queryToPlan(Query query, QuerySolution startSolution)
    {
        this.startSolution = startSolution ;
        if ( plan == null )
            plan = buildPlan(getModifiers(), query.getQueryPattern()) ;
        
        PlanElement pElt = getPlanElement() ;
        return new Plan1(pElt, this) ;
    }

    // ------------------------------------------------
    // Query Engine extension points.
    
    /** This operator is a hook for other query engines to reuse this framework but
     *  take responsibility for their own query pattern construction. 
     */
    protected PlanElement makePlanForQueryPattern(Context context, Element queryPatternElement)
    {
        // Choose the thing to make a plan of
        // This can be null - no WHERE clause.
        if ( queryPatternElement == null )
            return null ;
        return QueryPatternCompiler.makePlan(context, queryPatternElement) ;
    }
    
    /** Inspect, and possibily modify, the query plan and execution tree.
     * Called after plan creation getPlanForQueryPattern
     * 
     * @param context
     * @param planElt
     * @return PlanElement The plan element for the query pattern - often the PlanElement passed in
     */
    protected PlanElement queryPlanPatternHook(Context context, PlanElement planElt)
    { return planElt ; } 
    
    /** Inspect, and possibily modify, the query plan and execution tree.
     * Called after plan creation getPlanForQueryPattern
     * 
     * @param context
     * @param planElt
     * @return PlanElement  New root of the planning tree (often, the one passed in)
     */
    protected PlanElement queryPlanHook(Context context, PlanElement planElt)
    { return planElt ; } 
    
    // Build plan around the query pattern plan 
    
    private PlanElement buildPlan(Modifiers mods, Element pattern)
    {
        if ( plan != null )
            return plan ;
        
             // Remember the part of the plan that is specifically for the query pattern
        planPattern = makePlanForQueryPattern(getContext(), pattern) ;
        
        // Give subclasses a chance to run
        planPattern = queryPlanPatternHook(getContext(), planPattern) ;
        PlanElement planElt = planPattern ;
    
        // -- Modifiers
        
        // ORDER BY
        if ( mods.orderConditions != null )
            planElt = PlanOrderBy.make(getContext(), planElt, mods.orderConditions) ;
        
        // Project (ORDER may involve an unselected variable)
        // No projection => initial variables are exposed.
        // Needed for CONSTRUCT and initial bindings + SELECT *
        
        if ( mods.projectVars != null && ! query.isQueryResultStar())
        {
            // Don't project for QueryResultStar so initial bindings show through
            // in SELECT *
            if ( mods.projectVars.size() == 0 && query.isSelectType() )
                log.warn("No project variables") ;
            if ( mods.projectVars.size() > 0 ) 
                planElt = PlanProject.make(getContext(), planElt, mods.projectVars) ;
        }
        
        // DISTINCT
        if ( query.isDistinct() || getContext().isTrue(ARQConstants.autoDistinct) )
            planElt = PlanDistinct.make(getContext(), planElt, mods.projectVars) ;
        
        // LIMIT/OFFSET
        if ( query.hasLimit() || query.hasOffset() )
            planElt = PlanLimitOffset.make(getContext(), planElt, mods.start, mods.length) ;
    
        plan = planElt ;
        plan = queryPlanHook(getContext(), plan) ;
        return plan ;
    }

    // Turn a plan for the whole query into a results iterator.
    QueryIterator planToIterator(PlanElement pElt)
    {
        // Create query execution context
        DatasetGraph dsg = super.getDatasetGraph() ;
        ExecutionContext execContext = new ExecutionContext(getContext(),
                                                            dsg.getDefaultGraph(),
                                                            dsg) ;

        QueryIterator qIter = null ;
        try {
            init() ;
            if ( ! queryExecutionInitialised )
                throw new ARQInternalErrorException("Query execution not initialized") ;

            Binding rootBinding = buildInitialBinding() ;
            QueryIterator initialIter = new QueryIterSingleton(rootBinding, execContext) ;
            
            // Any WHERE clause ?
            if ( pElt == null )
            {
                if ( startSolution != null )
                    return initialIter ;
                else
                    return new QueryIterNullIterator(execContext) ;
            }

            qIter = pElt.build(initialIter, execContext) ;
            qIter = QueryIteratorCheck.check(qIter, execContext) ;
            return qIter ;
        } catch (RuntimeException ex) {
            if ( qIter != null )
                qIter.close();
            throw ex ;
        }
    }

    private Binding buildInitialBinding()
    {
        Binding rootBinding = makeRootBinding() ;
        
        if ( startSolution == null )
            return rootBinding ;

        Binding b = new BindingMap(rootBinding) ;
        BindingUtils.addToBinding(b, startSolution) ;
        return b ;
    }
    
    private static Binding makeRootBinding()
    {
        Binding rootBinding = BindingRoot.create() ;
//        Calendar cal = new GregorianCalendar() ;
//        String lex = Utils.calendarToXSDDateTimeString(cal) ;
//        Node n = Node.createLiteral(lex, null, XSDDatatype.XSDdateTime) ;
//        rootBinding.add(ARQConstants.varCurrentTime, n) ;
        return rootBinding ;
    }

    protected Modifiers getModifiers()
    {
        Modifiers mods = new Modifiers(query) ;
        if ( query.isConstructType() )
            // Need to expose the initial bindings - no projection at all. 
            mods.projectVars = null ;
        return mods ;
    }

    private static class Modifiers
    {
        // And construct needs to avoid a projection.
        public long start ;
        public long length ;
        public boolean distinct ;
        public List projectVars ;      // Null for no projection
        public List orderConditions ;

        public Modifiers(Query query)
        {
            start = query.getOffset() ;
            length = query.getLimit() ;
            distinct = query.isDistinct() ;
            projectVars = Var.varList(query.getResultVars()) ;
            orderConditions = query.getOrderBy() ;
        }
    }
    
    // -------- Factory
    private static QueryEngineFactory factory = new QueryEngineFactory()
    {
        public boolean accept(Query query, Dataset dataset) 
        { return true ; }

        public QueryExecution create(Query query, Dataset dataset)
        {
            QueryEngine1 engine = new QueryEngine1(query) ;
            engine.setDataset(dataset) ;
            return engine ;
        }
    } ;
}

/*
 *  (c) Copyright 2004, 2005, 2006, 2007 Hewlett-Packard Development Company, LP
 *  All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 * 3. The name of the author may not be used to endorse or promote products
 *    derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE AUTHOR ``AS IS'' AND ANY EXPRESS OR
 * IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 * OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 * IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT, INDIRECT,
 * INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
 * NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF
 * THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
