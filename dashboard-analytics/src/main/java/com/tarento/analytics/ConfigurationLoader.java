package com.tarento.analytics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.ResourcePatternUtils;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.Map;

@Component("configurationLoader")
public class ConfigurationLoader {

    private static Logger logger = LoggerFactory.getLogger(ConfigurationLoader.class);
    private Map<String, ObjectNode> nameContentMap = new HashMap<>();
    @Autowired
    private ResourceLoader resourceLoader;
    @Autowired
    private ObjectMapper objectMapper;
    private static final String RESOURCE_LOCATION = "classpath*:schema/*.json";
    public static final String ROLE_DASHBOARD_CONFIG = "RoleDashboardConfig.json";



    /**
     * Loads config resources
     * @throws Exception
     */
    @PostConstruct
	public void loadResources() throws Exception {
		Resource[] resources = getResources(RESOURCE_LOCATION);

		for (Resource resource : resources) {
			String jsonContent = getContent(resource);
            ObjectNode jsonNode = (ObjectNode) objectMapper.readTree(jsonContent);
            nameContentMap.put(resource.getFilename(), jsonNode);
		}
		logger.info("Number of resources loaded " + nameContentMap.size());

	}

    /**
     * Obtains a ObjectNode w.r.t given resource/file name in classpath*:schema
     * @param name
     * @return
     */
    public ObjectNode get(String name) {
        return nameContentMap.get(name);
    }

    /**
     * Loads all the resources/files with a given pattern *.json
     * @param pattern   path with *json
     * @return
     * @throws IOException
     */
    private Resource[] getResources(String pattern) throws IOException {
        Resource[] resources = ResourcePatternUtils.getResourcePatternResolver(resourceLoader).getResources(pattern);
        return resources;
    }

    /**
     * Returns a content of resource
     * 
     * @param resource
     * @return
     */
    private String getContent(Resource resource) {
        String content = null;
        try {
            InputStream is = resource.getInputStream();
            byte[] encoded = IOUtils.toByteArray(is);
            content = new String(encoded, Charset.forName("UTF-8"));

        } catch (IOException e) {
            logger.error("Cannot load resource " + resource.getFilename());

        } 
        return content;
    }

}