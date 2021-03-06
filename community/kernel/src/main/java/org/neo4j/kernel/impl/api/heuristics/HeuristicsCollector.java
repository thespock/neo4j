/**
 * Copyright (c) 2002-2014 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.kernel.impl.api.heuristics;

import java.io.Serializable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.neo4j.graphdb.Direction;
import org.neo4j.helpers.collection.Iterables;
import org.neo4j.kernel.api.heuristics.HeuristicsData;
import org.neo4j.kernel.impl.util.statistics.LabelledDistribution;
import org.neo4j.kernel.impl.util.statistics.RollingAverage;

public class HeuristicsCollector implements Serializable, HeuristicsData {

    private static final long serialVersionUID = 5430534253089297623L;

    private final LabelledDistribution<Integer> labels;
    private final LabelledDistribution<Integer> relationships;

    private final Map</*label*/Integer, Map</*rel*/Integer, RollingAverage>> outgoingDegrees = new HashMap<>();
    private final Map</*label*/Integer, Map</*rel*/Integer, RollingAverage>> incomingDegrees = new HashMap<>();
    private final Map</*label*/Integer, Map</*rel*/Integer, RollingAverage>> bothDegrees = new HashMap<>();

    private final NodeLivenessData nodeLivenessData;

    private final transient RollingAverage.Parameters parameters;

    public HeuristicsCollector()
    {
        this( new RollingAverage.Parameters() );
    }

    public HeuristicsCollector( RollingAverage.Parameters parameters )
    {
        this.parameters = parameters;
        this.nodeLivenessData = new NodeLivenessData( parameters );
        this.labels = new LabelledDistribution<>( parameters.equalityTolerance );
        this.relationships = new LabelledDistribution<>( parameters.equalityTolerance );

        outgoingDegrees.put(RELATIONSHIP_DEGREE_FOR_NODE_WITHOUT_LABEL, new HashMap<Integer, RollingAverage>() );
        incomingDegrees.put(RELATIONSHIP_DEGREE_FOR_NODE_WITHOUT_LABEL, new HashMap<Integer, RollingAverage>() );
        bothDegrees.put(RELATIONSHIP_DEGREE_FOR_NODE_WITHOUT_LABEL, new HashMap<Integer, RollingAverage>() );
    }

    public void addNodeObservation( List<Integer> nodeLabels, List<Integer> nodeRelTypes,
                                    Map<Integer, Integer> nodeIncoming, Map<Integer, Integer> nodeOutgoing )
    {
        labels.record( nodeLabels );
        relationships.record( nodeRelTypes );

        recordNodeDegree( nodeLabels, nodeIncoming, incomingDegrees );
        recordNodeDegree( nodeLabels, nodeOutgoing, outgoingDegrees );

        recordNodeDegree( nodeLabels, nodeIncoming, bothDegrees );
        recordNodeDegree( nodeLabels, nodeOutgoing, bothDegrees );

        nodeLivenessData.recordLiveEntity();
    }

    public void addSkippedNodeObservation()
    {
        nodeLivenessData.recordDeadEntity();
    }

    public void addMaxNodesObservation( long maxNodes ) { nodeLivenessData.setMaxEntities(maxNodes); }

    private void recordNodeDegree( List<Integer> nodeLabels,
                                   Map<Integer, Integer> source,
                                   Map<Integer, Map<Integer, RollingAverage>> degreeMap )
    {
        for ( Map.Entry<Integer, Integer> entry : source.entrySet() )
        {
            for ( Integer nodeLabel :
                    Iterables.append( /* Include for looking up without label */
                            RELATIONSHIP_DEGREE_FOR_NODE_WITHOUT_LABEL,
                                      nodeLabels
                    )
                 )
            {
                Map<Integer, RollingAverage> reltypeMap = degreeMap.get( nodeLabel );
                if(reltypeMap == null)
                {
                    reltypeMap = new HashMap<>();
                    degreeMap.put( nodeLabel, reltypeMap );
                }

                RollingAverage histogram = reltypeMap.get( entry.getKey() );
                if(histogram == null)
                {
                    histogram = new RollingAverage( parameters );
                    reltypeMap.put( entry.getKey(), histogram );
                }

                histogram.record( entry.getValue() );
            }
        }
    }

    public void recalculate()
    {
        labels.recalculate();
        relationships.recalculate();
        nodeLivenessData.recalculate();
    }

    @Override
    public double labelDistribution(int labelId)
    {
        return labels.get(labelId);
    }

    @Override
    public double relationshipTypeDistribution(int relType)
    {
        return relationships.get(relType);
    }

    @Override
    public double degree(int labelId, int relType, Direction direction)
    {
        Map<Integer, Map<Integer, RollingAverage>> labelMap;
        switch ( direction )
        {
            case INCOMING:
                labelMap = incomingDegrees;
                break;
            case OUTGOING:
                labelMap = outgoingDegrees;
                break;
            default:
                labelMap = bothDegrees;
        }

        if(labelMap.containsKey( labelId ))
        {
            Map<Integer, RollingAverage> relTypeMap = labelMap.get( labelId );
            if(relTypeMap.containsKey( relType ))
            {
                return relTypeMap.get( relType ).average();
            }
        }

        return 0.0;
    }

    @Override
    public double liveNodesRatio()
    {
        return nodeLivenessData.liveEntitiesRatio();
    }

    @Override
    public long maxAddressableNodes()
    {
        return nodeLivenessData.maxAddressableEntities();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        HeuristicsCollector that = (HeuristicsCollector) o;

        return
            bothDegrees.equals(that.bothDegrees)
            && incomingDegrees.equals(that.incomingDegrees)
            && outgoingDegrees.equals(that.outgoingDegrees)
            && nodeLivenessData.equals(that.nodeLivenessData)
            && labels.equals(that.labels)
            && relationships.equals(that.relationships);
    }

    @Override
    public int hashCode() {
        int result = labels.hashCode();
        result = 31 * result + relationships.hashCode();
        result = 31 * result + outgoingDegrees.hashCode();
        result = 31 * result + incomingDegrees.hashCode();
        result = 31 * result + bothDegrees.hashCode();
        result = 31 * result + nodeLivenessData.hashCode();
        return result;
    }
}

