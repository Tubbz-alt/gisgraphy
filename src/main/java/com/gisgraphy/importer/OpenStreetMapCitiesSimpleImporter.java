/*******************************************************************************
 *   Gisgraphy Project 
 * 
 *   This library is free software; you can redistribute it and/or
 *   modify it under the terms of the GNU Lesser General Public
 *   License as published by the Free Software Foundation; either
 *   version 2.1 of the License, or (at your option) any later version.
 * 
 *   This library is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 *   Lesser General Public License for more details.
 * 
 *   You should have received a copy of the GNU Lesser General Public
 *   License along with this library; if not, write to the Free Software
 *   Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA 02111-1307, USA
 * 
 *  Copyright 2008  Gisgraphy project 
 *  David Masclet <davidmasclet@gisgraphy.com>
 *  
 *  
 *******************************************************************************/
package com.gisgraphy.importer;

import static com.gisgraphy.domain.geoloc.entity.GisFeature.NAME_MAX_LENGTH;
import static com.gisgraphy.fulltext.Constants.ONLY_ADM_PLACETYPE;
import static com.gisgraphy.fulltext.FulltextQuerySolrHelper.MIN_SCORE;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.hibernate.FlushMode;
import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Required;

import com.gisgraphy.domain.geoloc.entity.Adm;
import com.gisgraphy.domain.geoloc.entity.City;
import com.gisgraphy.domain.geoloc.entity.CitySubdivision;
import com.gisgraphy.domain.geoloc.entity.GisFeature;
import com.gisgraphy.domain.geoloc.entity.ZipCode;
import com.gisgraphy.domain.repository.IAdmDao;
import com.gisgraphy.domain.repository.ICityDao;
import com.gisgraphy.domain.repository.ICitySubdivisionDao;
import com.gisgraphy.domain.repository.IGisFeatureDao;
import com.gisgraphy.domain.repository.IIdGenerator;
import com.gisgraphy.domain.repository.ISolRSynchroniser;
import com.gisgraphy.domain.valueobject.GISSource;
import com.gisgraphy.domain.valueobject.NameValueDTO;
import com.gisgraphy.domain.valueobject.Output;
import com.gisgraphy.domain.valueobject.Output.OutputStyle;
import com.gisgraphy.domain.valueobject.Pagination;
import com.gisgraphy.fulltext.Constants;
import com.gisgraphy.fulltext.FullTextSearchEngine;
import com.gisgraphy.fulltext.FulltextQuery;
import com.gisgraphy.fulltext.FulltextResultsDto;
import com.gisgraphy.fulltext.IFullTextSearchEngine;
import com.gisgraphy.fulltext.SolrResponseDto;
import com.gisgraphy.helper.AdmStateLevelInfo;
import com.gisgraphy.helper.GeolocHelper;
import com.gisgraphy.helper.StringHelper;
import com.gisgraphy.service.ServiceException;
import com.gisgraphy.util.StringUtil;
import com.vividsolutions.jts.geom.Geometry;
import com.vividsolutions.jts.geom.Point;

/**
 * Import the cities from an (pre-processed) openStreet map data file.
 * The goal of this importer is to cross information between geonames and Openstreetmap. 
 * Geonames has no concept of city but of populated place (That can be a city, suburb or other)
 * By cross the informations we can add shape and set a 'municipality' flag to identify city.
 * 
 * 
 * @author <a href="mailto:david.masclet@gisgraphy.com">David Masclet</a>
 */
public class OpenStreetMapCitiesSimpleImporter extends AbstractSimpleImporterProcessor {
	
	

	public static final int SCORE_LIMIT = 1;
	
	public final static int BATCH_UPDATE_SIZE = 100;

	protected static final Logger logger = LoggerFactory.getLogger(OpenStreetMapCitiesSimpleImporter.class);
	
    public static final Output DEFAUL_OUTPUT_STYLE = Output.withDefaultFormat().withStyle(OutputStyle.LONG);
    
    protected IIdGenerator idGenerator;
    
    protected ICityDao cityDao;
        
    protected ICitySubdivisionDao citySubdivisionDao;
    
    protected IAdmDao admDao;
    
    protected IGisFeatureDao gisFeatureDao;
    
    protected ISolRSynchroniser solRSynchroniser;
    
    protected IFullTextSearchEngine fullTextSearchEngine;
    
    protected IMunicipalityDetector municipalityDetector;
    
    LabelGenerator generator = LabelGenerator.getInstance();
    
    

    /* (non-Javadoc)
     * @see com.gisgraphy.domain.geoloc.importer.AbstractImporterProcessor#flushAndClear()
     */
    @Override
    protected void flushAndClear() {
    	cityDao.flushAndClear();
    }
    
    @Override
    protected void setup() {
        super.setup();
        //temporary disable logging when importing
        FullTextSearchEngine.disableLogging=true;
        logger.info("sync idgenerator");
        idGenerator.sync();
    }
    

    /* (non-Javadoc)
     * @see com.gisgraphy.domain.geoloc.importer.AbstractImporterProcessor#getFiles()
     */
    @Override
    protected File[] getFiles() {
	return ImporterHelper.listCountryFilesToImport(importerConfig.getOpenStreetMapCitiesDir());
    }

    /* (non-Javadoc)
     * @see com.gisgraphy.domain.geoloc.importer.AbstractImporterProcessor#getNumberOfColumns()
     */
    @Override
    protected int getNumberOfColumns() {
	return 16;
    }

    /* (non-Javadoc)
     * @see com.gisgraphy.domain.geoloc.importer.AbstractImporterProcessor#processData(java.lang.String)
     */
    @Override
    protected void processData(String line) throws ImporterException {
	String[] fields = line.split("\t");
	String countrycode=null;
	String name=null;
	Point location=null;
	Geometry shape=null;;
	Point adminCentreLocation=null;
	int  adminLevel =0;
	Long osmId = 0L;
	
	
	//
	// old Line table has the following fields :
	// --------------------------------------------------- 
	//0: N|W|R; 1 id; 2 name; 3 countrycode; 4 :postcode 
	//5:population 6:location; 7 : shape ;8: place tag; 9 : is_in;
	// 10 : alternatenames
	//
	// new Line table has the following fields :
	// --------------------------------------------------- 
	//0:	N|W|R ;1 : id;	2 :admin_centre_node_id; 3 : name;	4 : countrycode; 5 : postcode;	6 : postcode_subdivision; 7 : admin level;
	//	8 : population;	9 : location;	10 : admin_centre location 11 : shape;	12 : place tag	13 : is_in ; 14 : is_in_adm;
	//	15 : alternatenames;   

	
	checkNumberOfColumn(fields);
	
	
	// name
	if (!isEmptyField(fields, 3, false)) {
		name=fields[3].trim();
		if (name.length() > NAME_MAX_LENGTH){
			logger.warn(name + "is too long");
			name= name.substring(0, NAME_MAX_LENGTH-1);
		}
	}
	
	if (name==null){
		return;
	}
	
	//countrycode
	if (!isEmptyField(fields, 4, true)) {
	    countrycode=fields[4].trim().toUpperCase();
	}
	//location
	if (!isEmptyField(fields, 9, false)) {
	    try {
	    	location = (Point) GeolocHelper.convertFromHEXEWKBToGeometry(fields[9]);
	    } catch (RuntimeException e) {
	    	logger.warn("can not parse location for "+fields[9]+" : "+e);
	    	return;
	    }
	}
	//shape
		if(!isEmptyField(fields, 11, false)){
			try {
				shape = (Geometry) GeolocHelper.convertFromHEXEWKBToGeometry(fields[11]);
			    } catch (RuntimeException e) {
			    	logger.warn("can not parse shape for id "+fields[1]+" : "+e);
			    }
		}
	
	//admin_centre_location
		if (!isEmptyField(fields, 10, false)) {
		    try {
		    	adminCentreLocation = (Point) GeolocHelper.convertFromHEXEWKBToGeometry(fields[10]);
		    } catch (RuntimeException e) {
		    	logger.warn("can not parse admin centre location for "+fields[10]+" : "+e);
		    }
		}
		
	
	GisFeature place ;
	Integer population=null;
	Integer elevation=null;
	Integer gtopo30 = null;
	String timezone=null;
	String asciiName=null;
	if (isPoi(fields[12],countrycode, fields[7])) {//the feature to import is a poi
		SolrResponseDto  poiToremove = getNearestByPlaceType(location, name, countrycode,Constants.CITY_AND_CITYSUBDIVISION_PLACETYPE, shape, null);
		if (poiToremove!=null){
			//we found a Geonames city or subdivision that is not a municipality
				GisFeature cityToRemoveObj = null;
				if (poiToremove.getPlacetype().equalsIgnoreCase(City.class.getSimpleName())){
					cityToRemoveObj = cityDao.getByFeatureId(poiToremove.getFeature_id());
					
				}else if (poiToremove.getPlacetype().equalsIgnoreCase(CitySubdivision.class.getSimpleName())){
					 cityToRemoveObj = citySubdivisionDao.getByFeatureId(poiToremove.getFeature_id());
				}
				if (cityToRemoveObj!=null){
					population=cityToRemoveObj.getPopulation();
					elevation=cityToRemoveObj.getElevation();
					gtopo30 = cityToRemoveObj.getGtopo30();
					timezone=cityToRemoveObj.getTimezone();
					asciiName=cityToRemoveObj.getAsciiName();
					if((cityToRemoveObj.getPopulation()==null || (cityToRemoveObj.getPopulation()!=null && cityToRemoveObj.getPopulation()==0))){ //there is no population, we delete it
						logger.error("'"+name+"'/'"+fields[1]+"'changetype : is a poi we remove the city / citySubdivision "+cityToRemoveObj.getName()+","+cityToRemoveObj.getFeatureId()+" in the datastore");
						gisFeatureDao.remove(cityToRemoveObj);
						//create the poi
						place = createNewPoi(name, countrycode, location, adminCentreLocation);
						setGeonamesFields(place,0,elevation,gtopo30,timezone,asciiName);
					} else { //there is some population we choose to keep it as city or subdivision
						logger.error("'"+name+"'/'"+fields[1]+"' : is a poi but due to population "+cityToRemoveObj.getPopulation()+" we won't remove the city / citySubdivision "+cityToRemoveObj.getName()+","+cityToRemoveObj.getFeatureId()+" in the datastore");
						place=cityToRemoveObj;
					}
				} else {
					place = createNewPoi(name, countrycode, location, adminCentreLocation);
				}
		} else {
			place = createNewPoi(name, countrycode, location, adminCentreLocation);
		}
	}else if (StringUtil.containsDigit(name) || isACitySubdivision(fields[12],countrycode,fields[7])){// the feature to import is a subdivision
		SolrResponseDto  nearestCity = getNearestByPlaceType(location, name, countrycode,Constants.CITY_AND_CITYSUBDIVISION_PLACETYPE, shape, CitySubdivision.class);
		if (nearestCity != null ){
			if (nearestCity.getPlacetype().equalsIgnoreCase(CitySubdivision.class.getSimpleName())){// we found a subdivision, we will update it
				place = citySubdivisionDao.getByFeatureId(nearestCity.getFeature_id());
				if (place==null){
					place = createNewCitySubdivision(name,countrycode,location,adminCentreLocation);
					
				} else{ 
					place.setSource(GISSource.GEONAMES_OSM);
					//generally osm data is better than geonames, we overide geonames values
					if (name!=null){
						if (place.getOpenstreetmapId()==null ){
							//if osmid is not null, the name has already been set by previous line,
							//and because relation are before node the relation one is probably better
							place.setName(name);
						}
					}
					place.setCountryCode(countrycode);
					if (adminCentreLocation!=null){
						place.setAdminCentreLocation(adminCentreLocation);
					}
					if (location!=null){
						place.setLocation(location);
					}
				}
				
			} else if (nearestCity.getPlacetype().equalsIgnoreCase(City.class.getSimpleName())){ //we found a city
				if (nearestCity.getOpenstreetmap_id()==null && !nearestCity.isMunicipality()){//geonames feature that is not a municipality
					//osm consider the place as a suburb, but geonames consider it as a city,we delete the geonames one
					City cityToRemove = cityDao.getByFeatureId(nearestCity.getFeature_id());
					if (cityToRemove!=null){
						logger.error("changetype : '"+name+"'/'"+fields[1]+"' is a subdivision we remove  the city "+nearestCity.getName()+","+nearestCity.getFeature_id()+" in the datastore");
						// population=cityToRemove.getPopulation();
						 elevation=cityToRemove.getElevation();
						 gtopo30 = cityToRemove.getGtopo30();
						 timezone=cityToRemove.getTimezone();
						 asciiName=cityToRemove.getAsciiName();
						cityDao.remove(cityToRemove);
					}
				} else { //osm feature
					//we got a suburb and osm consider it as a city too, we keep both
				}
				// and create a citysubdivision
				place = createNewCitySubdivision(name,countrycode,location,adminCentreLocation);
				setGeonamesFields(place,0,elevation,gtopo30,timezone,asciiName);
				
			}
			else {
				place = createNewCitySubdivision(name,countrycode,location,adminCentreLocation);
				setGeonamesFields(place,0,elevation,gtopo30,timezone,asciiName);
			}
			
		} else {
			logger.warn("'"+name+"'/'"+fields[1]+"' is not in datastore, we create a new one");
			place = createNewCitySubdivision(name,countrycode,location,adminCentreLocation);
		}
		
	}  else { //the feature to import is a city
		SolrResponseDto  nearestCity = getNearestByPlaceType(location, name, countrycode, Constants.ONLY_CITY_PLACETYPE, shape, null);
		if (nearestCity != null ){
			place = cityDao.getByFeatureId(nearestCity.getFeature_id());
			if (place==null){
				place = createNewCity(name,countrycode,location,adminCentreLocation);

			} else { //we already got a city in datastore, we update it
				place.setSource(GISSource.GEONAMES_OSM);
				//generally osm data is better than geonames, we overide geonames values
				if (name!=null && place.getOpenstreetmapId()==null){
					//if osmid is not null, the name has already been set by previous line,
					//and because relation are before node the relation one is probably better
					place.setName(name);
				}
				place.setCountryCode(countrycode);
				if (adminCentreLocation!=null){
					place.setAdminCentreLocation(adminCentreLocation);
				}
				if (location!=null && place.getOpenstreetmapId()==null){
						place.setLocation(location);
				}
			}
		} else {
			place = createNewCity(name,countrycode,location,adminCentreLocation);
		}
		//set municipality if needed
		if ( !((City)place).isMunicipality()){ 
			//only if not already a city, because, a node can be after a relation and then node set the municipality to false
			((City)place).setMunicipality(municipalityDetector.isMunicipality(countrycode, fields[12], fields[0], GISSource.OSM));
		}
		if ("locality".equalsIgnoreCase(fields[12])){
			((City)place).setMunicipality(false);
		}
	}
	//populate new fields
	//population
	if(!isEmptyField(fields, 8, false) && !(place instanceof CitySubdivision)){
		try {
			String populationStr = fields[8];
			population = parsePopulation(populationStr);
			place.setPopulation(population);
		} catch (NumberFormatException e) {
			logger.error("can not parse population :"+fields[8]+" for "+fields[1]);
		}
	}
	//zip code
	if(!isEmptyField(fields, 5, false) && (place.getZipCodes()==null || !place.getZipCodes().contains(new ZipCode(fields[5],countrycode)))){
			populateZip(fields[5], place);
	}
	//subdivision zip code
		if(!isEmptyField(fields, 6, false) && (place.getZipCodes()==null || !place.getZipCodes().contains(new ZipCode(fields[6],countrycode)))){
				populateZip(fields[6], place);
		}
	
	if (place.getZipCodes()!=null && place.getZipCodes().size()>0){
		place.setZipCode(generator.getBestZip(place.getZipCodes()));
	}
	//place tag/amenity
	if(!isEmptyField(fields, 12, false)){
		place.setAmenity(fields[12]);
		
	}
	
	//set shape
	place.setShape(shape);
	
	//osmId
	if (place.getOpenstreetmapId()==null){
		//we do not override the osm ID because if it is filled, we are probably with a node and it 
		//has already been filled by a relation
		if (!isEmptyField(fields, 1, true)) {
			String osmIdAsString =fields[1].trim();

			try {
				osmId = Long.parseLong(osmIdAsString);
				place.setOpenstreetmapId(osmId);
			} catch (NumberFormatException e) {
				logger.error("can not parse openstreetmap id "+ osmIdAsString);
			}
		}
	}
	//adm level, we need it to populate adms
	if (!isEmptyField(fields, 7, true)) {
		String adminLevelStr =fields[7].trim();
		
		try {
			adminLevel = Integer.parseInt(adminLevelStr);
		} catch (NumberFormatException e) {
			logger.error("can not parse admin level "+adminLevelStr+" for "+osmId);
		}
	}
	
	//populate alternatenames
	if (!isEmptyField(fields, 15, false)) {
		String alternateNamesAsString=fields[15].trim();
		populateAlternateNames(place,alternateNamesAsString);
	}

	
	//isinadm
	if(!isEmptyField(fields, 14, false)){
		List<AdmDTO> adms = ImporterHelper.parseIsInAdm(fields[14]);
		populateAdmNames(place,adminLevel,adms);
		if (place.getAdm()==null){
			LinkAdm(place,adms);
		}
	} 
	else if(!isEmptyField(fields, 13, false)){
		if (place.getAdm()==null){
			String admname =fields[13];
			SolrResponseDto solrResponseDto= getAdm(admname,countrycode);
			if (solrResponseDto!=null){
				Adm adm = admDao.getByFeatureId(solrResponseDto.getFeature_id());
				if (adm!=null){
					place.setAdm(adm);
					populateAdmNamesFromAdm(place, adm);
				}
			}
		}
	}
	place.setAlternateLabels(generator.generateLabels(place));
	place.setLabel(generator.generateLabel(place));
	place.setFullyQualifiedName(generator.getFullyQualifiedName(place));
	//postal is not set because it is only for street
	
	try {
		savecity(place);
	} catch (ConstraintViolationException e) {
		logger.error("Can not save "+dumpFields(fields)+"(ConstraintViolationException) we continue anyway but you should consider this",e);
	}catch (Exception e) {
		logger.error("Can not save "+dumpFields(fields)+" we continue anyway but you should consider this",e);
	}

    }

	private void setGeonamesFields(GisFeature place, Integer population,
			Integer elevation, Integer gtopo30, String timezone,
			String asciiName) {
		if (place!=null){
			place.setPopulation(population);
			place.setElevation(elevation);
			place.setGtopo30(gtopo30);
			place.setTimezone(timezone);
			place.setAsciiName(asciiName);
		}
		
	}

	protected int parsePopulation(String populationStr) {
		int population = Integer.parseInt(populationStr.replaceAll("[\\s\\,]", ""));
		return population;
	}
    
	

	protected boolean isPoi(String placeType,String countryCode,String admLevel) {
		if ("locality".equalsIgnoreCase(placeType) && !AdmStateLevelInfo.isCityLevel(countryCode, admLevel)) {
			return true;
		}
		return false;
	}

	protected void LinkAdm(GisFeature city, List<AdmDTO> adms) {
		if (adms!=null){
			Collections.reverse(adms);
			for (AdmDTO admDTO:adms){
				if (admDTO.getAdmName()!=null){
					SolrResponseDto solrResponseDto= getAdm(admDTO.getAdmName(),city.getCountryCode());
					if (solrResponseDto!=null && solrResponseDto.getFeature_id()!=null){
						Adm adm = admDao.getByFeatureId(solrResponseDto.getFeature_id());
						if (adm != null){
							city.setAdm(adm);
							Collections.reverse(adms);
							return;
						}
					}
					
				}
			}
		}
		
		
	}

	protected boolean isACitySubdivision(String placeType,String countryCode ,String admLevel) {
		if (placeType.equalsIgnoreCase("city") || placeType.equalsIgnoreCase("village") || placeType.equalsIgnoreCase("town") || placeType.equalsIgnoreCase("hamlet")){
			return false;
		}
		if ("neighbourhood".equalsIgnoreCase(placeType)
				|| "quarter".equalsIgnoreCase(placeType)
				|| "isolated_dwelling".equalsIgnoreCase(placeType)
				|| "suburb".equalsIgnoreCase(placeType)
				|| "city_block".equalsIgnoreCase(placeType)
				|| "borough".equalsIgnoreCase(placeType)||
				(AdmStateLevelInfo.isCitySubdivisionLevel(countryCode, admLevel))
				) {
			return true;
		}
		return false;
	}
	
	

	/**
     * @param fields
     *                The array to process
     * @return a string which represent a human readable string of the Array but without shape because it is useless in logs
     */
    protected static String dumpFields(String[] fields) {
	String result = "[";
	for (int i=0;i<fields.length;i++) {
		if (i==11){
			result= result+"THE_SHAPE;";
		}else {
	    result = result + fields[i] + ";";
		}
	}
	return result + "]";
    }

	protected void populateZip(String zipAsString, GisFeature city) {
			String[] zips = zipAsString.split(";|\\||,");
			for (int i = 0;i<zips.length;i++){
				String zipcode = zips[i];
				if (!ImporterHelper.isUnwantedZipCode(zipcode) 
						)
						{
					city.addZipCode(new ZipCode(zipcode,city.getCountryCode()));
				}
			}
		
	}

	void savecity(GisFeature city) {
		if (city!=null){
			if (city instanceof City){
				cityDao.save((City)city);
			} else if (city instanceof CitySubdivision){
				citySubdivisionDao.save((CitySubdivision)city);
			} else {
				gisFeatureDao.save(city);
			}
		}
	}

	City createNewCity(String name,String countryCode,Point location,Point adminCentreLocation) {
		City city = new City();
		city.setFeatureId(idGenerator.getNextFeatureId());
		city.setSource(GISSource.OSM);
		city.setName(name);
		city.setLocation(location);
		city.setAdminCentreLocation(adminCentreLocation);
		city.setCountryCode(countryCode);
		return city;
	}
	
	GisFeature createNewPoi(String name,String countryCode,Point location,Point adminCentreLocation) {
		GisFeature city = new GisFeature();
		city.setFeatureId(idGenerator.getNextFeatureId());
		city.setSource(GISSource.OSM);
		city.setName(name);
		city.setLocation(location);
		city.setAdminCentreLocation(adminCentreLocation);
		city.setCountryCode(countryCode);
		return city;
	}
	

	CitySubdivision createNewCitySubdivision(String name,String countryCode,Point location,Point adminCentreLocation) {
		CitySubdivision city = new CitySubdivision();
		city.setFeatureId(idGenerator.getNextFeatureId());
		city.setSource(GISSource.OSM);
		city.setName(name);
		city.setLocation(location);
		city.setAdminCentreLocation(adminCentreLocation);
		city.setCountryCode(countryCode);
		return city;
	}
	
	GisFeature populateAlternateNames(GisFeature feature,
			String alternateNamesAsString) {
		return ImporterHelper.populateAlternateNames(feature,alternateNamesAsString);
		
	}
	
	
	

	protected SolrResponseDto getNearestByPlaceType(Point location, String name,String countryCode,Class[] placetypes, Geometry shape, Class target) {
		if (location ==null || name==null || "".equals(name.trim())){
			return null;
		}
		FulltextQuery query;
		try {
			if (placetypes==null){
				query = (FulltextQuery) new FulltextQuery(name).around(location).withoutSpellChecking().withPagination(Pagination.ONE_RESULT).withOutput(DEFAUL_OUTPUT_STYLE);
			} else {
				query = (FulltextQuery) new FulltextQuery(name).withPlaceTypes(placetypes).around(location).withoutSpellChecking().withPagination(Pagination.ONE_RESULT).withOutput(DEFAUL_OUTPUT_STYLE);
			}
			
		} catch (IllegalArgumentException e) {
			logger.error("can not create a fulltext query for "+name);
			return null;
		}
		if (countryCode != null){
			query.limitToCountryCode(countryCode);
		}
		FulltextResultsDto results;
        try {
            results = fullTextSearchEngine.executeQuery(query);
        } catch (RuntimeException e) {
            logger.error("error executing fulltext query for "+name+" : " +e);
            return null;
        }
		
		
		if (results != null && results.getResults()!=null && results.getResults().size()>0){
			if (placetypes.length == 1 || (target != null && target==GisFeature.class) || target==null){
				//we search for city or sub only only or (we search for a poi) or we don't care about the placetype to merge=> we don't care about the placetype found, first one not updated is OK
				if (results.getResults()!=null && results.getResults().size()>0){
					for (SolrResponseDto solrResponseDto : results.getResults()) {
						if (solrResponseDto!=null){
						if (isSame(solrResponseDto,name,shape)){
							if (solrResponseDto.getOpenstreetmap_id()!=null){
								//we already got a place that has been updated for this name, dont search more; it is not a place to delete
								return null;
							} else {
								if (!solrResponseDto.isMunicipality()){
									//we have a place that has not been updated yet and that is not a municipality
									return solrResponseDto;
								}
							}
						}}
					}
						
				}
			} else { //we got two placetype to search
				if (target != null){// if we search for a sub, && target==CitySubdivision.class
					SolrResponseDto first = results.getResults().get(0);
					if (first!=null){
						if (first.getPlacetype().equalsIgnoreCase(City.class.getSimpleName())){// if first is a city (updated or not)
							//remove first
							SolrResponseDto filtered = getFirstResultNotUpdatedByPlaceType(results.getResults(),name,shape,target);
							if (filtered==null){//if no sub after that are not updated
									if (first.getOpenstreetmap_id()==null){	//if city not updated => only one city not updated
										//we are in the case when geonames consider a place as city but it is not : delete the city and return null, so we will create a new sub
										//warning we can delete the city, it won't be updated later because there is no sub found, just a city it is probably a placetype problems (PPL is too global)
										return first;
									} else {//if city updated=>only one city that is already updated
										return null;
									}
							} else {//if sub after that that are not updated
								if (first.getOpenstreetmap_id()!=null){//if the first city is updated=>got a city updated and then a sub that is not
									//return first sub that is not already updated, that's the one to merge
									return filtered;
								} else {//if the first city is not updated and we got a sub not updated too
									//for now just log, we don't want to delete the city maybe it will be updtaed later
									logger.error("ambiguous : first is city "+first.getName()+"/"+first.getFeature_id()+" then we got a sub "+ filtered.getName()+"/"+filtered.getFeature_id());
									return filtered;
									//??????????????????????????????????=>we got a city and a sub that are not updated
									//we should return the city and return the sub
									//should we update the city? filter on shape ?
								}
							}
						}
						else if (first.getPlacetype().equalsIgnoreCase(CitySubdivision.class.getSimpleName()) && first.getOpenstreetmap_id()==null){//if first is a sub not updated
							return first;//we got a sub that is not yet updated, it is the one to merge
						}
					}
				}
			}
			
		}	
		return null;	
			
			
		/*	for (SolrResponseDto solrResponseDto : results.getResults()) {
				if (solrResponseDto!=null 
						){
					if (solrResponseDto.getOpenstreetmap_id()!= null){
						continue;
						//we are only interested in osm feature
						//if openstreetmap id is not null it is because the shape has already been set
						//(R are before nodes), we ignore because we don't want the place if relation has been set
					}
					String name2 = solrResponseDto.getName();
					//score is important for case when we search Munchen and city name is Munich
					if (name2!=null && StringHelper.isSameName(name, name2) || solrResponseDto.getScore() > MIN_SCORE || StringHelper.isSameAlternateNames(name,solrResponseDto.getName_alternates())){
						if (shape!=null && shape.isValid()){
							//we should verify
							if (solrResponseDto.getLng()!=null && solrResponseDto.getLat()!=null){
								boolean isInShape = false;
								try {
									Point point = GeolocHelper.createPoint(solrResponseDto.getLng(),solrResponseDto.getLat());
									isInShape = shape.contains(point);
								} catch (Exception e) {
									logger.error("can not determine if city is in shape for "+name+" in "+countryCode+" :  "+e.getMessage());
								}
								if (isInShape){
									return solrResponseDto;
								}
							} else {
								logger.error("no GPS coordinate for "+solrResponseDto);
							} 
						} else {
							//if the name is the same, and there is no shape
							return solrResponseDto;
						}
					}
				} else {
					return null;
				}
			}*/
		
		
	}
	
	
	private SolrResponseDto getFirstResultNotUpdatedByPlaceType(
			List<SolrResponseDto> results, String name, Geometry shape,
			Class target) {
		if (results!=null){
			for (SolrResponseDto dto : results){
				if (dto!=null){
					if (dto.getOpenstreetmap_id()==null && dto.getPlacetype().equalsIgnoreCase(target.getSimpleName())){
						return dto;
					}
				}
			}
		}
		return null;
	}

	protected boolean isSame(SolrResponseDto solrResponseDto,
			String name, Geometry shape) {
		if (solrResponseDto!=null){
		String name2 = solrResponseDto.getName();
		//score is important for case when we search Munchen and city name is Munich
		if (name !=null && name2!=null && StringHelper.isSameName(name, name2) || solrResponseDto.getScore() > MIN_SCORE || StringHelper.isSameAlternateNames(name,solrResponseDto.getName_alternates())){
			if (shape!=null && shape.isValid()){
				//we should verify
				if (solrResponseDto.getLng()!=null && solrResponseDto.getLat()!=null){
					boolean isInShape = false;
					try {
						Point point = GeolocHelper.createPoint(solrResponseDto.getLng(),solrResponseDto.getLat());
						isInShape = shape.contains(point);
					} catch (Exception e) {
						logger.error("can not determine if city is in shape for "+name+" in "+solrResponseDto.getFeature_id()+" :  "+e.getMessage());
					}
					if (isInShape){
						return true;
					}
				} else {
					logger.error("no GPS coordinate for "+solrResponseDto);
				} 
			} else {
				//if the name is the same, and there is no shape
				return true;
			}
		}
		}
		return false;
	}

	protected GisFeature populateAdmNames(GisFeature gisFeature, int currentLevel, List<AdmDTO> admdtos){
		
		return ImporterHelper.populateAdmNames(gisFeature, currentLevel, admdtos);
		
	}
	
	protected GisFeature populateAdmNamesFromAdm(GisFeature gisFeature,Adm adm){
		if (gisFeature ==null || adm ==null){
			return gisFeature;
		}
		String lastName="";
		int gisLevel = 1;
		for (int admlevel=1;admlevel <=5;admlevel++){
			if (adm !=null){
			String nameToSet = adm.getAdmName(admlevel);
			if (!lastName.equalsIgnoreCase(nameToSet) ){
				//only if adm level < or not set
				gisFeature.setAdmName(gisLevel++,nameToSet );
				if (nameToSet!=null){
					lastName = nameToSet;
				}
			}
			}
		}
		
		return gisFeature;
		
	}
	
	protected SolrResponseDto getAdm(String name, String countryCode) {
		if (name==null){
			return null;
		}
		FulltextQuery query;
		try {
			query = (FulltextQuery)new FulltextQuery(name).withAllWordsRequired(false).withoutSpellChecking().
					withPlaceTypes(ONLY_ADM_PLACETYPE).withOutput(DEFAUL_OUTPUT_STYLE).withPagination(Pagination.ONE_RESULT);
		} catch (IllegalArgumentException e) {
			logger.error("can not create a fulltext query for "+name);
			return null;
		}
		if (countryCode != null){
			query.limitToCountryCode(countryCode);
		}
		FulltextResultsDto results;
        try {
            results = fullTextSearchEngine.executeQuery(query);
        } catch (ServiceException e) {
            logger.error("error executing fulltext query for "+name+" : " +e);
            return null;
        }
		if (results != null){
			for (SolrResponseDto solrResponseDto : results.getResults()) {
				return solrResponseDto;
			}
		}
		return null;
	}
    

	/* (non-Javadoc)
     * @see com.gisgraphy.domain.geoloc.importer.AbstractImporterProcessor#shouldBeSkiped()
     */
    @Override
    public boolean shouldBeSkipped() {
    	return !importerConfig.isOpenstreetmapImporterEnabled();
    }
    
   


    /* (non-Javadoc)
     * @see com.gisgraphy.domain.geoloc.importer.AbstractImporterProcessor#setCommitFlushMode()
     */
    @Override
    protected void setCommitFlushMode() {
    	this.cityDao.setFlushMode(FlushMode.COMMIT);
    }

    /* (non-Javadoc)
     * @see com.gisgraphy.domain.geoloc.importer.AbstractImporterProcessor#shouldIgnoreComments()
     */
    @Override
    protected boolean shouldIgnoreComments() {
    	return true;
    }

    /* (non-Javadoc)
     * @see com.gisgraphy.domain.geoloc.importer.AbstractImporterProcessor#shouldIgnoreFirstLine()
     */
    @Override
    protected boolean shouldIgnoreFirstLine() {
    	return false;
    }

    /* (non-Javadoc)
     * @see com.gisgraphy.domain.geoloc.importer.IGeonamesProcessor#rollback()
     */
    public List<NameValueDTO<Integer>> rollback() {
    	List<NameValueDTO<Integer>> deletedObjectInfo = new ArrayList<NameValueDTO<Integer>>();
    	logger.info("reseting openstreetmap cities...");
    	//TODO only cities that have source openstreetmap
    	    deletedObjectInfo
    		    .add(new NameValueDTO<Integer>(City.class.getSimpleName(), 0));
    	resetStatus();
    	return deletedObjectInfo;
    }
    
    @Override
    //TODO test
    protected void tearDown() {
    	super.tearDown();
    	String savedMessage = this.statusMessage;
    	/*try {
    		this.statusMessage = internationalisationService.getString("import.updatecitysubdivision");
			int nbModify = citySubdivisionDao.linkCitySubdivisionToTheirCity();
			logger.warn(nbModify +" citySubdivision has been modify");
		} catch (Exception e){
			logger.error("error during link city subdivision to their city",e);
		}finally {
			 // we restore message in case of error
    	    this.statusMessage = savedMessage;
		}*/
    	try {
    		this.statusMessage = internationalisationService.getString("import.fixpolygon");
			logger.info("fixing polygons for city");
			int nbModify = cityDao.fixPolygons();
			logger.warn(nbModify +" polygons has been fixed");
		} catch (Exception e){
			logger.error("error durin fixing polygons",e);
		}
    	finally {
    	    this.statusMessage = savedMessage;
		}
    	FullTextSearchEngine.disableLogging=false;
    	/*try {
    		this.statusMessage = internationalisationService.getString("import.fulltext.optimize");
    		solRSynchroniser.optimize();
    		logger.warn("fulltext engine has been optimized");
    	}  catch (Exception e){
			logger.error("error durin fulltext optimization",e);
		}finally {
    	    // we restore message in case of error
    	    this.statusMessage = savedMessage;
    	}*/
    }
   
    
    
   

    @Required
    public void setSolRSynchroniser(ISolRSynchroniser solRSynchroniser) {
        this.solRSynchroniser = solRSynchroniser;
    }

    @Required
    public void setIdGenerator(IIdGenerator idGenerator) {
        this.idGenerator = idGenerator;
    }

    @Required
    public void setCityDao(ICityDao cityDao) {
		this.cityDao = cityDao;
	}
    
    @Required
    public void setGisFeatureDao(IGisFeatureDao gisFeatureDao) {
		this.gisFeatureDao = gisFeatureDao;
	}
    

    @Required
	public void setFullTextSearchEngine(IFullTextSearchEngine fullTextSearchEngine) {
		this.fullTextSearchEngine = fullTextSearchEngine;
	}

    @Required
	public void setAdmDao(IAdmDao admDao) {
		this.admDao = admDao;
	}
    

    @Required
    public void setMunicipalityDetector(IMunicipalityDetector municipalityDetector) {
		this.municipalityDetector = municipalityDetector;
	}

    @Required
	public void setCitySubdivisionDao(ICitySubdivisionDao citySubdivisionDao) {
		this.citySubdivisionDao = citySubdivisionDao;
	}

    
}
