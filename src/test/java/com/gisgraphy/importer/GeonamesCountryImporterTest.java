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

import static org.junit.Assert.assertEquals;

import java.util.List;

import org.junit.Assert;
import org.easymock.EasyMock;
import org.junit.Test;

import com.gisgraphy.domain.repository.CountryDao;
import com.gisgraphy.domain.repository.ICountryDao;
import com.gisgraphy.domain.valueobject.NameValueDTO;

public class GeonamesCountryImporterTest {

    @Test
    public void rollbackShouldRollback() {
	GeonamesCountryImporter geonamesCountryImporter = new GeonamesCountryImporter();
	ICountryDao countryDao = EasyMock.createMock(ICountryDao.class);
	EasyMock.expect(countryDao.deleteAll()).andReturn(5);
	EasyMock.replay(countryDao);
	geonamesCountryImporter.setCountryDao(countryDao);
	List<NameValueDTO<Integer>> deleted = geonamesCountryImporter
		.rollback();
	assertEquals(1, deleted.size());
	assertEquals(5, deleted.get(0).getValue().intValue());
    }
    
    @Test
    public void shouldBeSkipShouldReturnCorrectValue(){
	GeonamesCountryImporter geonamesCountryImporter = new GeonamesCountryImporter();
	CountryDao countryDao = EasyMock.createMock(CountryDao.class);
	EasyMock.expect(countryDao.count()).andReturn(10L);
	EasyMock.expect(countryDao.count()).andReturn(0L);
	EasyMock.replay(countryDao);
	
	geonamesCountryImporter.setCountryDao(countryDao);
	
	Assert.assertTrue("country importer should be skiped if there is some country imported",geonamesCountryImporter.shouldBeSkipped());
	Assert.assertFalse("country importer should be skiped if there is some country imported",geonamesCountryImporter.shouldBeSkipped());
	
		
    }

}
