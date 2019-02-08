package com.gisgraphy.importer;


import org.junit.Assert;
import org.junit.Test;

public class OpenAddressesSimpleImporterTest {

	@Test
	public void extractCountrycode() {
		OpenAddressesSimpleImporter importer = new OpenAddressesSimpleImporter();
		Assert.assertEquals("AR", importer.extractCountrycode("ar:d9728f782bae5457"));
		Assert.assertEquals(null, importer.extractCountrycode("d9728f782bae5457"));
		Assert.assertEquals(null, importer.extractCountrycode(""));
		Assert.assertEquals(null, importer.extractCountrycode(null));
	}
	
	@Test
	public void testIsAllRequiredFieldspresent(){
		OpenAddressesSimpleImporter importer = new OpenAddressesSimpleImporter();
		String[] array = {"","",""};
		Assert.assertFalse(importer.isAllRequiredFieldspresent(array));
		
		 array = new String[] {"","b",""};
		Assert.assertFalse(importer.isAllRequiredFieldspresent(array));
		
		 array = new String[] {"","","c"};
		Assert.assertFalse(importer.isAllRequiredFieldspresent(array));
		
		 array = new String[] {"a","b",""};
		Assert.assertFalse(importer.isAllRequiredFieldspresent(array));
		
		 array = new String[] {"","b","c"};
		Assert.assertFalse(importer.isAllRequiredFieldspresent(array));
		
		 array = new String[] {"a","",""};
		Assert.assertFalse(importer.isAllRequiredFieldspresent(array));
		
		 array = new String[]{"a","b","c"};
		Assert.assertTrue(importer.isAllRequiredFieldspresent(array));
		
	}
	
	
	
	@Test
	public void isZeroHouseNumber(){
		OpenAddressesSimpleImporter importer = new OpenAddressesSimpleImporter();
		Assert.assertTrue(importer.isZeroHouseNumber("0"));
		Assert.assertTrue(importer.isZeroHouseNumber("00"));
		Assert.assertFalse(importer.isZeroHouseNumber(""));
		Assert.assertFalse(importer.isZeroHouseNumber(null));
		Assert.assertFalse(importer.isZeroHouseNumber("01"));
	}
	
	@Test
	public void isUnWantedHouseNumber(){
		OpenAddressesSimpleImporter importer = new OpenAddressesSimpleImporter();
		Assert.assertTrue(importer.isUnWantedHouseNumber("0"));
		Assert.assertTrue(importer.isUnWantedHouseNumber("SN"));
		Assert.assertFalse(importer.isUnWantedHouseNumber("2 SN"));
		Assert.assertFalse(importer.isUnWantedHouseNumber("3"));
	}
	
	@Test
	public void isUnWantedStreetName(){
		OpenAddressesSimpleImporter importer = new OpenAddressesSimpleImporter();
		Assert.assertTrue(importer.isUnWantedStreetName("UNAVAILABLE"));
		Assert.assertTrue(importer.isUnWantedStreetName("NULL"));
		Assert.assertTrue(importer.isUnWantedStreetName("UNDEFINED"));
		Assert.assertTrue(importer.isUnWantedStreetName(""));
		Assert.assertTrue(importer.isUnWantedStreetName(" "));
		Assert.assertTrue(importer.isUnWantedStreetName(null));
		Assert.assertFalse(importer.isUnWantedStreetName("nr"));
		Assert.assertFalse(importer.isUnWantedStreetName("foo"));
	}
	
	@Test
	public void cleanNumber(){
		OpenAddressesSimpleImporter importer = new OpenAddressesSimpleImporter();
		Assert.assertEquals(null, importer.cleanNumber(""));
		Assert.assertEquals(null, importer.cleanNumber(" "));
		Assert.assertEquals(null, importer.cleanNumber(" 000000000000000000 "));
		Assert.assertEquals(null, importer.cleanNumber("000000000000000000"));
		Assert.assertEquals("3", importer.cleanNumber("0003"));
		
		Assert.assertEquals("3", importer.cleanNumber("#0003"));
		Assert.assertEquals("3", importer.cleanNumber("#3"));

	}

	@Test
	public void correctLines(){
		OpenAddressesSimpleImporter importer = new OpenAddressesSimpleImporter();
		Assert.assertEquals(null,importer.correctLine(null));
		String line="-54.6119061,-20.5140889,000819,\"AVENIDA JOANA D,ARC\",TEMPLO,,,,,,br:6b1a081120273836";
		Assert.assertEquals("-54.6119061,-20.5140889,000819,AVENIDA JOANA D ARC,TEMPLO,,,,,,br:6b1a081120273836",importer.correctLine(line));
		line="-54.6119061,-20.5140889,000819,AVENIDA JOANA D ARC\",TEMPLO,,,,,,br:6b1a081120273836";
		Assert.assertEquals("-54.6119061,-20.5140889,000819,AVENIDA JOANA D ARC\",TEMPLO,,,,,,br:6b1a081120273836",importer.correctLine(line));
		
		line="-54.6119061,-20.5140889,000819,\"AVENIDA JOANA D,ARC\",TEMPLO,\"bb,ff\",,,,,br:6b1a081120273836";
		org.junit.Assert.assertEquals("-54.6119061,-20.5140889,000819,AVENIDA JOANA D ARC,TEMPLO,bb ff,,,,,br:6b1a081120273836",importer.correctLine(line));
		
		//two comma
		 line="-51.9438429,-23.416729,20,RUA  BENJAMIN CONSTANT,\"LOJA 4,3,2,1\",Maringá,,PR,87020-060,,br:94cc7e00b7d822cd";
		Assert.assertEquals("-51.9438429,-23.416729,20,RUA  BENJAMIN CONSTANT,LOJA 4 3 2 1,Maringá,,PR,87020-060,,br:94cc7e00b7d822cd",importer.correctLine(line));
		
		 line="')$+.)-)',\"+($*++*/*)\",'\"I[[b[dX_dZ[hijhWÕ[\"\"8[hb_d\"\"\"'(+++\"\"Z[0W/'YY.+W+'Z+/\\/+";
			importer.correctLine(line);//should not throws when $
		
	}
	
	@Test
	public void extractCasaNumber(){
		OpenAddressesSimpleImporter importer = new OpenAddressesSimpleImporter();
		Assert.assertEquals(null,importer.extractCasaNumber(null));
		Assert.assertEquals(null,importer.extractCasaNumber("foo"));
		
		Assert.assertEquals("3",importer.extractCasaNumber("casa3"));
		Assert.assertEquals("3",importer.extractCasaNumber("casa 3"));
		
		Assert.assertEquals("32",importer.extractCasaNumber("casa  32"));
		
		Assert.assertEquals("32",importer.extractCasaNumber("CASA  32"));
		
		Assert.assertEquals("32",importer.extractCasaNumber(" lot 4 CASA  32"));
	}
	
}
