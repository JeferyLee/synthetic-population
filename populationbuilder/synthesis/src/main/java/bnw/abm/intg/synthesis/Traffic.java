package bnw.abm.intg.synthesis;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import bnw.abm.intg.util.*;
import org.geotools.feature.FeatureIterator;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.PopulationFactory;
import org.matsim.core.api.internal.MatsimWriter;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.opengis.feature.Feature;
import org.opengis.feature.simple.SimpleFeature;

import com.fasterxml.jackson.core.JsonParseException;
import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.Geometry;

import bnw.abm.intg.filemanager.BNWFiles;
import bnw.abm.intg.filemanager.csv.CSVReader;
import bnw.abm.intg.filemanager.csv.CSVWriter;
import bnw.abm.intg.filemanager.json.JSONReadable;
import bnw.abm.intg.filemanager.json.JSONWriter;
import bnw.abm.intg.filemanager.json.JacksonJSONReader;
import bnw.abm.intg.geo.CoordinateConversion;
import bnw.abm.intg.geo.FeatureProcessing;
import bnw.abm.intg.geo.ShapefileGeoFeatureReader;

/**
 * Hello world!
 *
 */
public class Traffic {


	public static void main(String[] args) {

		Log.createLogger("Traffic", "Traffic.log");
		Traffic ap = new Traffic();
		Path addressJsonZip = null, popHome = null, rawDatHome = null, sa2Shape = null, hhAddrJson = null,
				hhOutLoc = null, trafficXml = null;
		String hHoldRegex = null, agentRegex = null;
		String[] jsonPathToSA1code = null;
		int randomSeed = 0;
		/*
		 * Read properties
		 */
		if (args.length > 0) {
			BNWProperties props = null;
			try {

				props = new BNWProperties(args[0]);
			} catch (IOException e) {
				Log.error("When reading the innertraffic.properties file", e);
			}
			popHome = props.readFileOrDirectoryPath("PopulationHome");
			hHoldRegex = props.getProperty("HholdFileRegex");
			agentRegex = props.getProperty("AgentFileRegex");
			addressJsonZip = props.readFileOrDirectoryPath("InputAddressJson");
			hhAddrJson = props.readFileOrDirectoryPath("HhMappedAddressJson");
			jsonPathToSA1code = props.getProperty("JsonPathToSA1Code").trim().split("\\\\");
			rawDatHome = props.readFileOrDirectoryPath("RawDataHome");
			sa2Shape = props.readFileOrDirectoryPath("SA2ShapeFile");
			trafficXml = props.readFileOrDirectoryPath("TrafficPlan");
			hhOutLoc = props.readFileOrDirectoryPath("UPDATEDHholdFile");
			randomSeed = Integer.parseInt(props.getProperty("RandomSeed"));
		} else {
			System.err.println("Give path to config.properties as the first argument");
		}
		/*
		 * Get all household data csvs
		 */
		List<Path> hHoldFiles = BNWFiles.find(popHome, hHoldRegex);

		/* attribute titles in AgentsList csvs */
		String[] agentAttributes = { "AgentId", "AgentType", "PartnerId", "MotherId", "FatherId", "RelationshipStatus",
				"ChildrenIds", "Gender", "GroupSize", "Age", "GroupId", "CareNeedLevel", "Travel2Work",
				"PersonalIncome" };
		/* attribute titles in Household data csvs */
		String[] hholdAttributes = { "GroupId", "GroupType", "GroupSize", "Members", "Bedrooms", "DwellingStructure",
				"FamilyIncome", "Tenure&Landlord" };

		try {
			Random random = new Random(randomSeed);
			CSVReader csvr = new CSVReader();
			JSONReadable jsonR = new JacksonJSONReader();
			Map addressMap = jsonR.readJSONGz(addressJsonZip);

			// Group addresses by SA1. Addresses in addressesBySA1 still refer
			// to instances in addressMap. So any change to addressesBySA1
			// elements automatically reflected in addressMap
			Map<String, List<Map>> addrssesBySA1 = ap.groupAddressesBySA1(addressMap, jsonPathToSA1code);

			Map<String, ArrayList<LinkedHashMap<String, Object>>> allAgents = new HashMap<String, ArrayList<LinkedHashMap<String, Object>>>();
			allAgents.put("Agents", new ArrayList<LinkedHashMap<String, Object>>());
			Map<String, ArrayList<LinkedHashMap<String, Object>>> allHholds = new HashMap<String, ArrayList<LinkedHashMap<String, Object>>>();
			allHholds.put("Households", new ArrayList<LinkedHashMap<String, Object>>());
			HashMap<String, LinkedHashMap<String, Object>> agents;
			ArrayList<LinkedHashMap<String, Object>> hHolds = new ArrayList<>();

			// Initialise MATSim population construction
			Scenario matsimScenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
			PopulationFactory populationFactory = matsimScenario.getPopulation().getFactory();

			/*
			 * We do several things in this loop 1. Get the Household file of
			 * each SA1 and allocate a building-address (dwelling) to each
			 * household 2. Record starting address geometry location as
			 * starting coordinate in matsim plan
			 */
			int filecount = 0;
			for (Path hHoldFile : hHoldFiles) {
				String sa1id = ap.getSA1code(hHoldFile);
				Log.info( "Starting Household Data file: " + sa1id);
				final Reader hholdReader = new InputStreamReader(new FileInputStream(hHoldFile.toFile()), "UTF-8");
				hHolds = csvr.readCsvGroupByRow(hholdReader, hholdAttributes);

				allHholds.get("Households").addAll(hHolds);
				/*
				 * Read agents list of this SA1
				 */
				Path sa1dataloc = Paths.get(popHome + File.separator + sa1id);
				List<Path> agentListLoc = BNWFiles.find(sa1dataloc, agentRegex);
				agents = csvr.readCsvGroupByRow(new FileReader(agentListLoc.get(0).toFile()), "AgentId");

				/*
				 * Read work locations list of agents in this SA1
				 */
				Path workDestinationFile = Paths.get(rawDatHome + File.separator + sa1id + File.separator + "WL.csv");
				List<String> workLocations = csvr.readCsvRows(new FileReader(workDestinationFile.toFile())).get(0);
				workLocations = workLocations.subList(1, workLocations.size());

				/*
				 * Read geo feature of each SA2
				 */
				HashMap<String, Feature> sa2map = ap.getSA2Areas(sa2Shape.toAbsolutePath(), workLocations);

				/*
				 * 1. Allocate an address to each randomly selected household in
				 * this SA1
				 */
				Collections.shuffle(hHolds);
				for (int i = 0; i < hHolds.size(); i++) {
					if (!addrssesBySA1.containsKey(sa1id)) {
						break;
					}
					if (addrssesBySA1.get(sa1id).size() == i) {
						System.err.println("Not engouh buildings in " + sa1id + ". Total Households:" + hHolds.size()
								+ " Total Buildings: " + addrssesBySA1.get(sa1id).size());
						Log.info(
								"Not engouh buildings in " + sa1id + ". Total Households:" + hHolds.size()
										+ "Total Buildings: " + addrssesBySA1.get(sa1id).size());
						break;
					}

					addrssesBySA1.get(sa1id).get(i).put("HOUSEHOLD_ID", hHolds.get(i).get("GroupId"));
					hHolds.get(i).put("Address",
							((Map) addrssesBySA1.get(sa1id).get(i).get("properties")).get("EZI_ADD"));

					/**
					 * 2. Determine matsim start and end locations of members of
					 * this household
					 */
					Map<String, Map<String, List<Object>>> addr = addrssesBySA1.get(sa1id).get(i);
					List<Object> addrCoords = addr.get("geometry").get("coordinates");
					Map<String, Object> hHold = hHolds.get(i);
					ap.assignTravel2WorkOriginAndDestinations(addrssesBySA1,
							sa1id,
							hHold,
							agents,
							addrCoords,
							matsimScenario,
							sa2map,
							populationFactory,
							random);

				}
				++filecount;
				Log.info( "Completed Household Data file: " + sa1id);
				System.out.println("Data file: " + sa1id + "                                                       ");
				ConsoleProgressBar.updateProgress("Completed ",
						filecount / (float) hHoldFiles.size(),
						filecount + "/" + hHoldFiles.size() + "\r");
			}

			// Finally, write this population to file
			MatsimWriter popWriter = new org.matsim.api.core.v01.population.PopulationWriter(
					matsimScenario.getPopulation(), matsimScenario.getNetwork());
			popWriter.write(trafficXml.toAbsolutePath().toString());

			new JSONWriter().writeToJsonGzFile(addressMap, hhAddrJson);

			CSVWriter csvWriter = new CSVWriter();
			csvWriter.writeLinkedMapAsCsv(Files.newBufferedWriter(hhOutLoc), allHholds.get("Households"));

		} catch (JsonParseException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (FileNotFoundException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();

		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

	}

	private void assignTravel2WorkOriginAndDestinations(Map<String, List<Map>> addrssesBySA1,
			String sa1id,
			Map<String, Object> hHold,
			HashMap<String, LinkedHashMap<String, Object>> agents,
			List<Object> addrCoords,
			Scenario matsimScenario,
			Map<String, Feature> sa2map,
			PopulationFactory populationFactory,
			Random random) throws Exception {

		List<String> members = null;
		if (hHold.get("Members") instanceof List) {
			members = (List<String>) hHold.get("Members");
		} else if (hHold.get("Members") instanceof String) {
			members = Arrays.asList((String) hHold.get("Members"));
		} else {
			throw new Exception("Unexpected value");
		}
		for (String memId : members) {

			String travelMode = (String) agents.get(memId).get("Travel2Work");
			if (travelMode.equals("CarAsDriver")) {
				// List a = addr.get("geometry").get("coordinates");
				double easting = (double) addrCoords.get(0);
				double northing = (double) addrCoords.get(1);
				Coord startCoord = matsimScenario.createCoord(easting, northing);
				String sa2name = (String) agents.get(memId).get("Destination");
				Coord endCoord = this.getCoordinates(matsimScenario, sa2map, sa2name, random);
				Person person = this.createPersonWithPlan(populationFactory, startCoord, endCoord, memId, "car");
				if (matsimScenario.getPopulation().getPersons().containsKey(memId)) {
					System.out.println(matsimScenario.getPopulation().getPersons().get(memId));
				}
				matsimScenario.getPopulation().addPerson(person);

			}
		}
	}

	/**
	 * Group addresses in by SA1
	 * 
	 * @param addressMap
	 *            The map of all addresses
	 * @param jsonMatchProperty
	 *            The path to SA1 code in the json file
	 * @return A map of addresses by SA1 codes
	 * @throws IOException
	 *             When reading addresses json file
	 */
	private Map<String, List<Map>> groupAddressesBySA1(Map addressMap, String[] jsonMatchProperty) throws IOException {

		/*
		 * We are expecting a list of elements after root element
		 */
		if (!(addressMap.get(jsonMatchProperty[0]) instanceof List)) {
			System.err.println("Root element must have an arrray of address elements, but this not an array");
			Log.errorAndExit("Root element must have an arrray of address elements, but this not an array", GlobalConstants.EXITCODE.USERINPUT);
		}
		List featuresList = (List) addressMap.get(jsonMatchProperty[0]);

		if (!(featuresList.get(0) instanceof Map)) {
			String errorStr = "Expecting a map after " + jsonMatchProperty[0] + "\\" + jsonMatchProperty[1]
					+ " but you have something else in addressMap";
			System.err.println(errorStr);
			Log.errorAndExit(errorStr, GlobalConstants.EXITCODE.USERINPUT);
		}
		/*
		 * Store all the addresses in JSON grouped by their SA1
		 */
		Map<String, List<Map>> addrssesBySA1 = new HashMap();
		for (Object feature : featuresList) {
			Map mFeature = (Map) feature;
			String said = (String) ((HashMap) mFeature.get(jsonMatchProperty[1])).get(jsonMatchProperty[2]);

			if (said != null) {
				if (addrssesBySA1.containsKey(said)) {
					addrssesBySA1.get(said).add(mFeature);
				} else {
					addrssesBySA1.put(said, new ArrayList<Map>(Arrays.asList(mFeature)));
				}
			}

		}
		return addrssesBySA1;
	}

	/**
	 * Generates a coordinate for a person
	 * 
	 * @param matsimScenario
	 * @param sa2map
	 *            Map of SA2s
	 * @param area
	 *            The area code
	 * @return coordinate
	 */
	private Coord getCoordinates(Scenario matsimScenario, Map<String, Feature> sa2map, String area, Random random) {
		FeatureProcessing fp = new FeatureProcessing();
		Feature workSA = sa2map.get(area);
		if (workSA == null) { // No SA2 for - POW Capital city undefined
								// (Greater Melbourne). Taking random SA2
			int indx = (int) Math.random() * (sa2map.size() - 1);
			area = (String) sa2map.keySet().toArray()[indx];
			workSA = sa2map.get(area);
		}
		Geometry geom = fp.getRandomPointIn(sa2map.get(area), random);
		Coordinate coord = geom.getCoordinate();
		CoordinateConversion cc = new CoordinateConversion();
		Map utm = cc.latLon2UTM(coord.y, coord.x);
		return matsimScenario.createCoord((double) utm.get("easting"), (double) utm.get("northing"));
	}

	/**
	 * Gets SA1 code using Population directory names. The first directory that
	 * has a number as name is taken as SA1 code
	 * 
	 * @param hHoldFile path to the file
	 * @return The SA1 code
	 */
	private String getSA1code(Path hHoldFile) {

		Pattern p = Pattern.compile("[0-9]+");

		Matcher m = p.matcher(hHoldFile.toString());
		if (m.find()) {
			return m.group(0);
		}

		return null;
	}

	/**
	 * Copied from outer traffic
	 * 
	 * @param actType
	 * @return
	 */
	private double activityEndTime(String actType) {
		double endTime = 0.0;
		if (actType.equals("work")) {
			/*
			 * Allow people to leave work between 16.45 and 17.10
			 */
			endTime = 60300 + (60 * 25 * Math.random());
			return endTime;
		}
		return 21600;
	}

	/**
	 * Creates a person with a plan for MATSim
	 * 
	 * @param populationFactory
	 * @param homeCoord
	 * @param workCoord
	 * @param personId
	 * @param travelMode
	 * @return
	 */
	private Person createPersonWithPlan(PopulationFactory populationFactory,
			Coord homeCoord,
			Coord workCoord,
			String personId,
			String travelMode) {

		Plan plan = populationFactory.createPlan();

		// Create a new activity with the end time and add it to the plan
		// Sleeping at home
		Activity act = populationFactory.createActivityFromCoord("home", homeCoord);
		act.setEndTime(this.activityEndTime("home"));
		plan.addActivity(act);

		// Go to work
		plan.addLeg(populationFactory.createLeg(travelMode));

		// Do work
		act = populationFactory.createActivityFromCoord("work", workCoord);
		act.setEndTime(this.activityEndTime("work"));
		plan.addActivity(act);

		// Go home
		plan.addLeg(populationFactory.createLeg(travelMode));

		// Go to sleep
		act = populationFactory.createActivityFromCoord("home", homeCoord);
		plan.addActivity(act);

		Person person = populationFactory.createPerson(Id.createPersonId(personId));
		person.addPlan(plan);
		return person;
	}

	/**
	 * Read SA2 areas from shapefile to a map
	 * 
	 * @param sa2File
	 *            The shape file location
	 * @param sa2list
	 *            The list of SA2 areas to read
	 * @return A map of SA2 id and features in each SA2
	 * @throws IOException
	 *             When reading shapefiles
	 */
	private HashMap<String, Feature> getSA2Areas(Path sa2File, List<String> sa2list) throws IOException {

		HashMap<String, Feature> sa2map = new HashMap<>(sa2list.size());
		for (String loc : sa2list) {
			sa2map.put(loc, null);
		}

		String[] sa2names = sa2map.keySet().toArray(new String[0]);
		String property = "SA2_NAME11";
		ShapefileGeoFeatureReader geoReader = new ShapefileGeoFeatureReader();
		geoReader.loadFeaturesByProperty(sa2File, property, sa2names);

		try (FeatureIterator<Feature> featItr = geoReader.getFeatures().features()) {
			while (featItr.hasNext()) {
				SimpleFeature feature = (SimpleFeature) featItr.next();
				sa2map.put((String) feature.getAttribute("SA2_NAME11"), feature);
			}
		}
		return sa2map;
	}

}