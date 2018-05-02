package io.github.agentsoz.syntheticpop.addressmapper;

import io.github.agentsoz.syntheticpop.geo.FeatureProcessor;
import io.github.agentsoz.syntheticpop.util.LIFOLinkedHashSet;
import org.geotools.data.simple.SimpleFeatureCollection;
import org.geotools.data.simple.SimpleFeatureIterator;
import org.geotools.filter.text.cql2.CQL;
import org.geotools.filter.text.cql2.CQLException;
import org.opengis.feature.simple.SimpleFeature;
import org.opengis.filter.Filter;

import java.util.HashMap;
import java.util.HashSet;
import java.util.zip.DataFormatException;

/**
 * @author wniroshan 18 Apr 2018
 */
class Matcher {

    final private String saLookupKey;
    final private String addressLookupKey;
    final private FeatureProcessor featProcessor;

    final private HashMap<String, SimpleFeature> whiteList;
    final private HashSet<String> blackList;
    private LIFOLinkedHashSet<SimpleFeature> recentSAMatches = new LIFOLinkedHashSet<>(15);

    private int lookup = 0, contain = 0, whiteListHits = 0, blackListHits = 0;

    /**
     * Matches an address with a mesh block from ABS census data. This class is written in a way so it will work for other geographical
     * areas in addition to mesh blocks, i.e. to use this on SA1s simply replace mesh block data with SA1 data.
     *
     * @param featureProcessor Feature processor instance to perform shape file operations
     * @param saLookupKey      The key in Statistical Area shapefile attributes-table that refer to the ID of area (mesh block).
     * @param addressLookupKey The key in address shapefile attributes-table that refer to address' area (mesh block) ID.
     */
    Matcher(FeatureProcessor featureProcessor, String saLookupKey, String addressLookupKey) {
        this.saLookupKey = saLookupKey;
        this.addressLookupKey = addressLookupKey;
        this.featProcessor = featureProcessor;
        whiteList = new HashMap<>();
        blackList = new HashSet<>();
    }

    /**
     * Finds the SA mesh block of a the specified address. First the method tries to lookup the address mesh block id in SA mesh block
     * attributes table. If that fails, it tries to locate the geographical mesh block polygon that contains the address. If the correct SA
     * mesh block of the address is found the result is cached and returns the SA mesh block. Caching helps speeding up the search.
     *
     * @param address      The address feature
     * @param saMeshBlocks The collection of Statistical Area mesh blocks from ABS data
     * @return The Statistical Area mesh block feature of that contains the address
     * @throws CQLException        When looking up the address mesh block ID in saMeshBlocks collection
     * @throws DataFormatException When searching the the address within the saMeshBlocks polygons
     */
    SimpleFeature findSAMeshBlock(SimpleFeature address, SimpleFeatureCollection saMeshBlocks) throws CQLException, DataFormatException {

        SimpleFeature mb;
        if (isInWhiteList(address)) {
            mb = getSAMeshBlockFromWhiteList(address);
            whiteListHits++;
        } else if (isInBlackList(address)) {
            mb = null;
            blackListHits++;
        } else {
            mb = lookupMatchingSAMeshBlock(address, saMeshBlocks);
            if (mb == null) {
                mb = locateContainingSAMeshBlockOfAddress(address, saMeshBlocks);
                if (mb != null) {
                    whiteList.put(address.getAttribute(addressLookupKey).toString(), mb);
                    contain++;
                } else {
                    blackList.add(address.getAttribute(addressLookupKey).toString());
                }
            } else {
                whiteList.put(address.getAttribute(addressLookupKey).toString(), mb);
                lookup++;
            }
        }

        return mb;
    }

    /**
     * Returns performance stats
     *
     * @return String describing search result summary
     */
    String getStats() {
        return "Successful area ID lookup count: " + lookup + ", Successful polygon search count: " + contain + ", Final white list cache size: " + whiteList
                .size() + ", White list cache detections:" + whiteListHits + ", Final black list cache size: " + blackList.size() + ", Black list detections: " + blackListHits;
    }

    /**
     * Looks up the matching SA mesh block using address' mesh block ID
     *
     * @param address         The address feature
     * @param allSAMeshBlocks Collection of all SA mesh blocks
     * @return The matching SA mesh block if found. Else returns null
     * @throws CQLException If filter creation failed
     */
    private SimpleFeature lookupMatchingSAMeshBlock(SimpleFeature address, SimpleFeatureCollection allSAMeshBlocks) throws CQLException {

        SimpleFeature saMb = null;
        String addressMeshBlockId = address.getAttribute(addressLookupKey).toString();
        if (!(addressMeshBlockId == null || addressMeshBlockId.equals(""))) {
            Filter filter = CQL.toFilter(saLookupKey + "=" + addressMeshBlockId);

            SimpleFeatureCollection matchingSAMeshBlocks = allSAMeshBlocks.subCollection(filter);
            SimpleFeatureIterator matchingSAMeshBlockItr = matchingSAMeshBlocks.features();

            if(matchingSAMeshBlockItr.hasNext()){
                saMb = matchingSAMeshBlockItr.next();
            }

            matchingSAMeshBlockItr.close();
        }
        return saMb;
    }

    /**
     * Finds the SA mesh block (polygon) that address is contained geographically
     *
     * @param address      The address
     * @param saMeshBlocks The collection of SA mesh blocks
     * @return The SA mesh block that the address is in. If the mesh block is not found returns null
     * @throws DataFormatException When searching for the address within a polygon.
     */
    private SimpleFeature locateContainingSAMeshBlockOfAddress(SimpleFeature address,
                                                               SimpleFeatureCollection saMeshBlocks) throws DataFormatException {


        SimpleFeature matchingMeshBlock = null;

        if (!recentSAMatches.isEmpty()) {
            matchingMeshBlock = featProcessor.getContainingPolygon(recentSAMatches, address);
        }
        if (matchingMeshBlock == null) {
            matchingMeshBlock = featProcessor.getContainingPolygon(saMeshBlocks, address);
        }

        /*
         * Going through all the polygons every time we check a point is
         * very time consuming. So we keep a set of recently matched
         * polygons to see if the new point is within one of these polygons.
         * Rationale is points are likely to be clustered together
         * spatially. So we don't have to check full polygons list unless
         * the new point is outside the current cluster. Every time we find
         * a matching polygon we move it to the top of the recently matched
         * set. Size of recently matched polygon is a set to 15 (decided arbitrarily).
         */
        if (matchingMeshBlock != null) {
            recentSAMatches.add(matchingMeshBlock);
        }
        return matchingMeshBlock;

    }

    /**
     * Checks whether the matching mesh block of the address is already known
     *
     * @param address The address
     * @return True is the address's mesh block is already known
     */
    private boolean isInWhiteList(SimpleFeature address) {
        return whiteList.containsKey(address.getAttribute(addressLookupKey).toString());
    }

    private boolean isInBlackList(SimpleFeature address) {
        return blackList.contains(address.getAttribute(addressLookupKey).toString());
    }

    /**
     * Return the already matched SA mesh block of the specified address
     *
     * @param address The address
     * @return SA mesh block
     */
    private SimpleFeature getSAMeshBlockFromWhiteList(SimpleFeature address) {
        return whiteList.get(address.getAttribute(addressLookupKey).toString());
    }

}
