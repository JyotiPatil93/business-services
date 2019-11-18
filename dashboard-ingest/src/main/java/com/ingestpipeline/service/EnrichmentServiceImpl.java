package com.ingestpipeline.service;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.hash.Hashing;
import com.ingestpipeline.model.CollectionDomainConfig;
import com.ingestpipeline.model.DomainIndexConfig;
import com.ingestpipeline.model.TargetData;
import com.ingestpipeline.repository.TargetDataDao;

import com.ingestpipeline.util.JSONUtil;
import org.apache.tomcat.util.codec.binary.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;

import com.ingestpipeline.repository.ElasticSearchRepository;
import com.ingestpipeline.util.Constants;

/**
 * This is a Service Implementation for all the actions which are with respect
 * to Elastic Search
 * 
 * @author Darshan Nagesh
 *
 */
@Service(Constants.Qualifiers.ENRICHMENT_SERVICE)
public class EnrichmentServiceImpl implements EnrichmentService {

	public static final Logger LOGGER = LoggerFactory.getLogger(EnrichmentServiceImpl.class);
	private static final String SEPARATOR = "_";
	private static final String JSON_EXTENSION = ".json";
	private static final String OBJECTIVE = "enrichment";
	private final String indexServiceHost;
	private final String userName;
	private final String password;
	private final String elasticSearchIndexName;
	private final String elasticSearchDocumentType;

	private static final String AUTHORIZATION = "Authorization";
	private static final String US_ASCII = "US-ASCII";
	private static final String BASIC_AUTH = "Basic %s";

	private static final String BUSINESS_SERVICE = "businessService";
	private static final String DATA_OBJECT = "dataObject";

	@Autowired
	private ElasticSearchRepository elasticRepository;

	@Autowired
	private CollectionDomainConfig collectionDomainConfig;

	@Autowired
	private IESService elasticService;

	@Autowired
	private TargetDataDao targetDataDao;

	@Autowired
	private EnrichTransform enrichTransform;

	public EnrichmentServiceImpl(@Value("${services.esindexer.host}") String indexServiceHost,
			@Value("${services.esindexer.username}") String userName,
			@Value("${services.esindexer.password}") String password,
			@Value("${es.index.name}") String elasticSearchIndexName,
			@Value("${es.document.type}") String elasticSearchDocumentType) {
		this.indexServiceHost = indexServiceHost;
		this.userName = userName;
		this.password = password;
		this.elasticSearchIndexName = elasticSearchIndexName;
		this.elasticSearchDocumentType = elasticSearchDocumentType;
	}

	@Override
	public Boolean enrichData(Map incomingData) {
		if (incomingData.get("dataContext").toString().equalsIgnoreCase("target")) {
			ArrayNode values = new ObjectMapper().convertValue(incomingData.get(Constants.DATA_OBJECT), ArrayNode.class);
			//System.out.println("incomingData 1209 values " + values);

			values.forEach(val -> {
				TargetData targetData = new ObjectMapper().convertValue(val, TargetData.class);
				String hashId = targetData.getFinancialYear() + "-" + targetData.getBusinessService()+"-"+targetData.getUlbName();
				hashId = hashId.replaceAll("(\\s)+", "");
				String sha256hex = Hashing.sha256().hashString(hashId, StandardCharsets.UTF_8).toString();
				targetData.setId(sha256hex.hashCode());
				//targetData.setId(hashId);
				try{
					elasticService.push(targetData);
				}catch (Exception e ){
					e.printStackTrace();
				}

			});
		} else if(incomingData.get("dataContext").toString().equalsIgnoreCase("collection")){
			// prepare the query required based on incoming data businessType
			ObjectNode incomingNode = new ObjectMapper().convertValue(incomingData.get(DATA_OBJECT), ObjectNode.class);
			ObjectNode copyNode = incomingNode.deepCopy();
			String businessTypeVal = copyNode.findValue(BUSINESS_SERVICE).asText();

			DomainIndexConfig indexConfig = collectionDomainConfig.getIndexConfig(businessTypeVal.toString());
			String indexName = indexConfig.getIndexName();
			String query = indexConfig.getQuery();

			try {
				ObjectNode queryNode = new ObjectMapper().readValue(query, ObjectNode.class);

				indexConfig.getCollectionRef().forEach(collectionRef -> {
					StringBuffer argVal = new StringBuffer();
					String argument = collectionRef.getArgument();
					String fieldName = collectionRef.getFieldName();

					JSONUtil.findValue(incomingNode, fieldName, argVal); // argument value is search with fieldname and stored in argVal

					if (businessTypeVal.toString().equalsIgnoreCase("PT") && argument.equalsIgnoreCase("Data.propertyId")) {
						String [] array = argVal.toString().split(":");
						argVal = new StringBuffer();
						argVal.append(array[0]);

					} else if (businessTypeVal.toString().equalsIgnoreCase("PT") && argument.equalsIgnoreCase("Data.propertyDetails.assessmentNumber")) {
						String [] array = argVal.toString().split(":");
						argVal = new StringBuffer();
						argVal.append(array[1]);
					}

					JSONUtil.replaceFieldValue(queryNode, argument, argVal.toString()); // construct query: argument node value replaced by argVal
					LOGGER.info("Query node "+ queryNode);


				});
				// Hit Elastic search: pull record for the constructed query
				Map domainNode = elasticService.search(indexName, queryNode.toString());
				LOGGER.info("Fetched record from ES of a businessType "+ businessTypeVal + ": " + domainNode);

				//transform the request(s) using schema(s), and reform the incoming object
				Object transDomainResponse = enrichTransform.transform(domainNode, businessTypeVal.toString());

				incomingData.put("domainObject", transDomainResponse);
				//System.out.println("collection result: "+new ObjectMapper().convertValue(incomingData, JsonNode.class));

				//Push that transformed incoming object to ES - Collection new index.
				elasticService.push(incomingData);

			}catch (Exception e) {
				e.printStackTrace();
				LOGGER.error("Pre-processing - Fetching record from ES for " + businessTypeVal + "failed: " +  e.getMessage());
			}

		}

		return Boolean.TRUE;
	}

	private Boolean pushToElasticSearchIndex(Object object) {
		Long currentDateTime = new Date().getTime();
		String url = String.format("%s%s/%s/%s", this.indexServiceHost, elasticSearchIndexName,
				elasticSearchDocumentType, currentDateTime);
		HttpHeaders headers = getHttpHeaders();
		LOGGER.info("Data Object to be added to ES : " + object);
		LOGGER.info("URL to invoke : " + url);
		elasticRepository.saveMyDataObject(object, url, headers);
		return Boolean.TRUE;
	}

	/**
	 * A helper method to create the headers for Rest Connection with UserName and
	 * Password
	 * 
	 * @return HttpHeaders
	 */
	private HttpHeaders getHttpHeaders() {
		HttpHeaders headers = new HttpHeaders();
		headers.add(AUTHORIZATION, getBase64Value(userName, password));
		headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
		headers.setContentType(MediaType.APPLICATION_JSON);
		return headers;
	}

	/**
	 * Helper Method to create the Base64Value for headers
	 * 
	 * @param userName
	 * @param password
	 * @return
	 */
	private String getBase64Value(String userName, String password) {
		String authString = String.format("%s:%s", userName, password);
		byte[] encodedAuthString = Base64.encodeBase64(authString.getBytes(Charset.forName(US_ASCII)));
		return String.format(BASIC_AUTH, new String(encodedAuthString));
	}

}