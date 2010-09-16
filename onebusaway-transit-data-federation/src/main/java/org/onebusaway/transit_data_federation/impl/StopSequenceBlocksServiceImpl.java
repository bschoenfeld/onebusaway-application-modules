package org.onebusaway.transit_data_federation.impl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.onebusaway.collections.Counter;
import org.onebusaway.collections.FactoryMap;
import org.onebusaway.collections.Max;
import org.onebusaway.collections.tuple.Pair;
import org.onebusaway.collections.tuple.Tuples;
import org.onebusaway.gtfs.model.AgencyAndId;
import org.onebusaway.gtfs.model.Stop;
import org.onebusaway.gtfs.model.Trip;
import org.onebusaway.transit_data_federation.impl.tripplanner.DistanceLibrary;
import org.onebusaway.transit_data_federation.model.StopSequence;
import org.onebusaway.transit_data_federation.model.StopSequenceCollection;
import org.onebusaway.transit_data_federation.model.StopSequenceCollectionKey;
import org.onebusaway.transit_data_federation.services.StopSequenceCollectionService;
import org.onebusaway.transit_data_federation.services.TransitGraphDao;
import org.onebusaway.transit_data_federation.services.tripplanner.TripEntry;
import org.onebusaway.utility.collections.TreeUnionFind;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Construct a set of {@link StopSequenceCollection} blocks for each route. A block
 * contains a set of {@link StopSequence} sequences that are headed in the same
 * direction for a particular route, along with a general description of the
 * destinations for those stop sequences and general start and stop locations
 * for the sequences.
 * 
 * @author bdferris
 */
@Component
public class StopSequenceBlocksServiceImpl implements StopSequenceCollectionService {

  private static final double SERVICE_PATTERN_TRIP_COUNT_RATIO_MIN = 0.2;

  private static final double STOP_SEQUENCE_MIN_COMMON_RATIO = 0.3;

  private TransitGraphDao _graph;

  @Autowired
  public void setTransitGraphDao(TransitGraphDao graph) {
    _graph = graph;
  }

  /*
   * (non-Javadoc)
   * 
   * @seeorg.onebusaway.transit_data_federation.impl.StopSequenceBlocksService#
   * getStopSequencesAsBlocks(java.util.List)
   */
  public List<StopSequenceCollection> getStopSequencesAsBlocks(
      List<StopSequence> sequences) {

    pruneEmptyStopSequences(sequences);

    if (sequences.isEmpty())
      return new ArrayList<StopSequenceCollection>();

    Map<StopSequence, PatternStats> sequenceStats = getStatsForStopSequences(sequences);
    Map<String, List<StopSequence>> sequenceGroups = getGroupsForStopSequences(sequences);

    return constructBlocks(sequenceStats, sequenceGroups);
  }

  /**
   * Remove stop sequences from a list that do not contain any stops
   * 
   * @param stopSequences
   */
  private void pruneEmptyStopSequences(List<StopSequence> stopSequences) {
    for (Iterator<StopSequence> it = stopSequences.iterator(); it.hasNext();) {
      StopSequence st = it.next();
      if (st.getStops().isEmpty())
        it.remove();
    }
  }

  /**
   * Computes some general statistics for each {@link StopSequence} in a
   * collection, including the number of trips taking that stop sequence, the
   * set of regions for the destination of the stop sequence
   * 
   * @param sequences
   * @return the computed statistics
   */
  private Map<StopSequence, PatternStats> getStatsForStopSequences(
      List<StopSequence> sequences) {

    Map<StopSequence, PatternStats> patternStats = new HashMap<StopSequence, PatternStats>();

    for (StopSequence sequence : sequences) {
      PatternStats stats = new PatternStats();
      stats.tripCounts = sequence.getTripCount();
      stats.segment = getSegmentForStopSequence(sequence);
      patternStats.put(sequence, stats);
    }

    return patternStats;
  }

  /**
   * Compute a {@link Segment} object for the specified {@link StopSequence}. A
   * Segment generally captures the start and end location of the stop sequence,
   * along with the sequence's total length.
   * 
   * @param pattern
   * @return
   */
  private Segment getSegmentForStopSequence(StopSequence pattern) {

    Segment segment = new Segment();

    List<Stop> stops = pattern.getStops();
    Stop prev = null;

    for (Stop stop : stops) {
      if (prev == null) {
        segment.fromLat = stop.getLat();
        segment.fromLon = stop.getLon();
      } else {
        segment.distance += DistanceLibrary.distance(prev, stop);
      }
      segment.toLat = stop.getLat();
      segment.toLon = stop.getLon();
      prev = stop;
    }

    return segment;
  }

  /**
   * Group StopSequences by common direction. If all the stopSequences have a
   * direction id, then we use that to do the grouping. Otherwise...
   * 
   * @param sequences
   * 
   * @return
   */
  private Map<String, List<StopSequence>> getGroupsForStopSequences(
      List<StopSequence> sequences) {

    boolean allSequencesHaveDirectionId = true;

    for (StopSequence sequence : sequences) {
      if (sequence.getDirectionId() == null)
        allSequencesHaveDirectionId = false;
    }

    if (allSequencesHaveDirectionId) {
      Map<String, List<StopSequence>> result = groupStopSequencesByDirectionIds(sequences);
      if (result.size() > 1)
        return result;
    }

    return groupStopSequencesByNotDirectionIds(sequences);
  }

  /**
   * Group the StopSequences by their direction ids.
   * 
   * @param sequences
   * @return
   */
  private Map<String, List<StopSequence>> groupStopSequencesByDirectionIds(
      Iterable<StopSequence> sequences) {

    Map<String, List<StopSequence>> groups = new FactoryMap<String, List<StopSequence>>(
        new ArrayList<StopSequence>());

    for (StopSequence sequence : sequences) {
      String directionId = sequence.getDirectionId();
      groups.get(directionId).add(sequence);
    }

    return groups;
  }

  private Map<String, List<StopSequence>> groupStopSequencesByNotDirectionIds(
      Iterable<StopSequence> sequences) {

    TreeUnionFind<StopSequence> unionFind = new TreeUnionFind<StopSequence>();

    for (StopSequence stopSequenceA : sequences) {

      unionFind.find(stopSequenceA);

      for (StopSequence stopSequenceB : sequences) {
        if (stopSequenceA == stopSequenceB)
          continue;
        double ratio = getMaxCommonStopSequenceRatio(stopSequenceA,
            stopSequenceB);
        if (ratio >= STOP_SEQUENCE_MIN_COMMON_RATIO)
          unionFind.union(stopSequenceA, stopSequenceB);

      }
    }

    Map<String, List<StopSequence>> results = new HashMap<String, List<StopSequence>>();
    int index = 0;

    for (Set<StopSequence> sequencesByDirection : unionFind.getSetMembers()) {
      String key = Integer.toString(index);
      List<StopSequence> asList = new ArrayList<StopSequence>(
          sequencesByDirection);
      results.put(key, asList);
      index++;
    }

    return results;
  }

  /**
   * 
   * @param route
   * @param sequenceStats
   * @param sequencesByStopSequenceBlockId
   * @return
   */
  private List<StopSequenceCollection> constructBlocks(
      Map<StopSequence, PatternStats> sequenceStats,
      Map<String, List<StopSequence>> sequencesByStopSequenceBlockId) {

    computeContinuations(sequenceStats, sequencesByStopSequenceBlockId);

    Set<String> allNames = new HashSet<String>();
    Map<String, String> directionToName = new HashMap<String, String>();
    Map<String, Segment> segments = new HashMap<String, Segment>();

    for (Map.Entry<String, List<StopSequence>> entry : sequencesByStopSequenceBlockId.entrySet()) {

      String direction = entry.getKey();
      List<StopSequence> sequences = entry.getValue();

      Max<StopSequence> maxTripCount = new Max<StopSequence>();

      Counter<String> names = new Counter<String>();

      for (StopSequence sequence : sequences) {
        maxTripCount.add(sequence.getTripCount(), sequence);
        for (Trip trip : sequence.getTrips()) {
          String headsign = trip.getTripHeadsign();
          if (headsign != null && headsign.length() > 0)
            names.increment(headsign);
        }
      }

      String dName = names.getMax();

      RecursiveStats rs = new RecursiveStats();
      rs.maxTripCount = (long) maxTripCount.getMaxValue();

      exploreStopSequences(rs, sequenceStats, sequences, "");

      allNames.add(dName);
      directionToName.put(direction, dName);

      segments.put(direction, rs.longestSegment.getMaxElement());
    }

    if (allNames.size() < directionToName.size()) {
      for (Map.Entry<String, String> entry : directionToName.entrySet()) {
        String direction = entry.getKey();
        String name = entry.getValue();
        direction = direction.charAt(0) + direction.substring(1).toLowerCase();
        entry.setValue(name + " - " + direction);
      }
    }

    List<StopSequenceCollection> blocks = new ArrayList<StopSequenceCollection>();

    for (Map.Entry<String, String> entry : directionToName.entrySet()) {

      String direction = entry.getKey();
      String name = entry.getValue();
      List<StopSequence> patterns = sequencesByStopSequenceBlockId.get(direction);

      Segment segment = segments.get(direction);

      // System.out.println("  " + direction + " => " + name);
      StopSequenceCollection block = new StopSequenceCollection();

      if (segment.fromLat == 0.0)
        throw new IllegalStateException("what?");

      StopSequenceCollectionKey key = new StopSequenceCollectionKey(null, direction);
      block.setId(key);
      block.setPublicId(direction);
      block.setDescription(name);
      block.setStopSequences(patterns);
      block.setStartLat(segment.fromLat);
      block.setStartLon(segment.fromLon);
      block.setEndLat(segment.toLat);
      block.setEndLon(segment.toLon);

      blocks.add(block);
    }

    return blocks;
  }

  /**
   * For each given StopSequence, we wish to compute the set of StopSequences
   * that continue the given StopSequence. We say one StopSequence continues
   * another if the two stops sequences have the same route and direction id and
   * each trip in the first StopSequence is immediately followed by a Trip from
   * the second StopSequence, as defined by a block id.
   * 
   * @param sequenceStats
   * @param sequencesByStopSequenceBlockId
   */
  private void computeContinuations(
      Map<StopSequence, PatternStats> sequenceStats,
      Map<String, List<StopSequence>> sequencesByStopSequenceBlockId) {

    Set<Trip> trips = new HashSet<Trip>();
    Map<AgencyAndId, StopSequence> stopSequencesByTripId = new HashMap<AgencyAndId, StopSequence>();

    Map<StopSequence, String> stopSequenceBlockIds = new HashMap<StopSequence, String>();
    for (Map.Entry<String, List<StopSequence>> entry : sequencesByStopSequenceBlockId.entrySet()) {
      String id = entry.getKey();
      for (StopSequence sequence : entry.getValue())
        stopSequenceBlockIds.put(sequence, id);
    }

    for (StopSequence sequence : sequenceStats.keySet()) {
      for (Trip trip : sequence.getTrips()) {
        if (trip.getBlockId() != null) {
          if (trips.add(trip)) {
            stopSequencesByTripId.put(trip.getId(), sequence);
          }
        }
      }
    }

    for (AgencyAndId tripId : stopSequencesByTripId.keySet()) {

      TripEntry tripEntry = _graph.getTripEntryForId(tripId);
      TripEntry prevTrip = tripEntry.getPrevTrip();

      // No continuations if no incoming trip
      if (prevTrip == null)
        continue;

      StopSequence prevSequence = stopSequencesByTripId.get(prevTrip.getId());

      // No continuations if incoming is not part of the sequence collection
      if (prevSequence == null)
        continue;

      String prevGroupId = stopSequenceBlockIds.get(prevSequence);

      StopSequence stopSequence = stopSequencesByTripId.get(tripId);
      String groupId = stopSequenceBlockIds.get(stopSequence);

      // No continuation if it's the same stop sequence
      if (prevSequence.equals(stopSequence))
        continue;

      // No contination if the the block group ids don't match
      if (!groupId.equals(prevGroupId))
        continue;

      Stop prevStop = prevSequence.getStops().get(
          prevSequence.getStops().size() - 1);
      Stop nextStop = stopSequence.getStops().get(0);
      double d = DistanceLibrary.distance(prevStop, nextStop);
      if (d < 5280 / 4) {
        /*
         * System.out.println("distance=" + d + " from=" + prevStop.getId() +
         * " to=" + nextStop.getId() + " ssFrom=" + prevSequence.getId() +
         * " ssTo=" + stopSequence.getId());
         */
        PatternStats stats = sequenceStats.get(prevSequence);
        stats.continuations.add(stopSequence);
      }
    }
  }

  private void exploreStopSequences(RecursiveStats rs,
      Map<StopSequence, PatternStats> patternStats,
      Iterable<StopSequence> patterns, String depth) {

    Segment prevSegment = rs.prevSegment;

    for (StopSequence pattern : patterns) {

      if (rs.visited.contains(pattern))
        continue;

      PatternStats stats = patternStats.get(pattern);

      double count = stats.tripCounts;
      double ratio = count / rs.maxTripCount;

      if (ratio < SERVICE_PATTERN_TRIP_COUNT_RATIO_MIN)
        continue;

      Segment segment = stats.segment;

      if (prevSegment != null)
        segment = new Segment(prevSegment, segment, prevSegment.distance
            + segment.distance);

      rs.longestSegment.add(segment.distance, segment);

      Set<StopSequence> nextPatterns = stats.continuations;

      if (!nextPatterns.isEmpty()) {
        rs.visited.add(pattern);
        rs.prevSegment = segment;
        exploreStopSequences(rs, patternStats, nextPatterns, depth + "  ");
        rs.visited.remove(pattern);
      }
    }
  }

  private double getMaxCommonStopSequenceRatio(StopSequence a, StopSequence b) {
    Set<Pair<Stop>> pairsA = getStopSequenceAsStopPairSet(a);
    Set<Pair<Stop>> pairsB = getStopSequenceAsStopPairSet(b);
    int common = 0;
    for (Pair<Stop> pairA : pairsA) {
      if (pairsB.contains(pairA))
        common++;
    }

    double ratioA = ((double) common) / pairsA.size();
    double ratioB = ((double) common) / pairsB.size();
    return Math.max(ratioA, ratioB);
  }

  private Set<Pair<Stop>> getStopSequenceAsStopPairSet(StopSequence stopSequence) {
    Set<Pair<Stop>> pairs = new HashSet<Pair<Stop>>();
    Stop prev = null;
    for (Stop stop : stopSequence.getStops()) {
      if (prev != null) {
        Pair<Stop> pair = Tuples.pair(prev, stop);
        pairs.add(pair);
      }
      prev = stop;
    }
    return pairs;
  }

  /*
   * private static class BlockComparator implements Comparator<Trip> {
   * 
   * public BlockComparator() {
   * 
   * }
   * 
   * public int compare(Trip o1, Trip o2) { return o2.getBlockSequenceId() -
   * o1.getBlockSequenceId(); } }
   */

  private static class PatternStats {
    long tripCounts;
    Segment segment;
    Set<StopSequence> continuations = new HashSet<StopSequence>();
  }

  private static class RecursiveStats {
    Max<Segment> longestSegment = new Max<Segment>();
    Set<StopSequence> visited = new HashSet<StopSequence>();
    long maxTripCount;
    Segment prevSegment;
  }

  private static class Segment {

    double fromLon;
    double fromLat;
    double toLon;
    double toLat;
    double distance;

    public Segment() {

    }

    public Segment(Segment prevSegment, Segment toSegment, double d) {
      this.fromLat = prevSegment.fromLat;
      this.fromLon = prevSegment.fromLon;
      this.toLat = toSegment.toLat;
      this.toLon = toSegment.toLon;
      this.distance = d;
    }
  }
}
