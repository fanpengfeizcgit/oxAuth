/*
 * oxAuth is available under the MIT License (2008). See http://opensource.org/licenses/MIT for full text.
 *
 * Copyright (c) 2014, Gluu
 */

package org.xdi.oxauth.ws.rs.uma;

import org.jboss.resteasy.client.ClientResponseFailure;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Parameters;
import org.testng.annotations.Test;
import org.xdi.oxauth.BaseTest;
import org.xdi.oxauth.client.uma.UmaResourceService;
import org.xdi.oxauth.client.uma.UmaClientFactory;
import org.xdi.oxauth.client.uma.wrapper.UmaClient;
import org.xdi.oxauth.model.uma.UmaResourceResponse;
import org.xdi.oxauth.model.uma.UmaMetadata;
import org.xdi.oxauth.model.uma.UmaResource;
import org.xdi.oxauth.model.uma.UmaResourceWithId;
import org.xdi.oxauth.model.uma.UmaTestUtil;
import org.xdi.oxauth.model.uma.wrapper.Token;

import javax.ws.rs.core.Response;
import java.util.Arrays;
import java.util.List;

import static org.testng.Assert.*;

/**
 * Test cases for the registering UMA resources
 *
 * @author Yuriy Zabrovarnyy
 * @author Yuriy Movchan
 */
public class RegisterResourceFlowHttpTest extends BaseTest {

    protected UmaMetadata metadata;
    protected Token pat;

    protected String resourceId;
    protected UmaResourceService resourceService;

    public RegisterResourceFlowHttpTest() {
    }

    public RegisterResourceFlowHttpTest(UmaMetadata metadataConfiguration) {
        this.metadata = metadataConfiguration;
    }

    @BeforeClass
    @Parameters({"umaMetaDataUrl", "umaPatClientId", "umaPatClientSecret"})
    public void init(final String umaMetaDataUrl, final String umaPatClientId, final String umaPatClientSecret) throws Exception {
        if (this.metadata == null) {
            this.metadata = UmaClientFactory.instance().createMetadataService(umaMetaDataUrl, clientExecutor(true)).getMetadata();
            UmaTestUtil.assert_(this.metadata);
        }

        pat = UmaClient.requestPat(tokenEndpoint, umaPatClientId, umaPatClientSecret, clientExecutor(true));
        UmaTestUtil.assert_(pat);
    }

    public UmaResourceService getResourceService() throws Exception {
        if (resourceService == null) {
            resourceService = UmaClientFactory.instance().createResourceService(this.metadata, clientExecutor(true));
        }
        return resourceService;
    }

    /**
     * Add resource
     */
    @Test(dependsOnMethods = {"init"})
    public void addResource() throws Exception {
        showTitle("addResource");
        registerResource(Arrays.asList("http://photoz.example.com/dev/scopes/view", "http://photoz.example.com/dev/scopes/all"));
    }

    public String registerResource(List<String> scopes) throws Exception {
        UmaResourceResponse resourceStatus = null;
        try {
            UmaResource resource = new UmaResource();
            resource.setName("Photo Album");
            resource.setIconUri("http://www.example.com/icons/flower.png");
            resource.setScopes(scopes);

            resourceStatus = getResourceService().addResource("Bearer " + pat.getAccessToken(), resource);
        } catch (ClientResponseFailure ex) {
            System.err.println(ex.getResponse().getEntity(String.class));
            throw ex;
        }

        UmaTestUtil.assert_(resourceStatus);

        this.resourceId = resourceStatus.getId();
        return this.resourceId;
    }

    /**
     * Resource modification
     */
    @Test(dependsOnMethods = {"addResource"})
    public void modifyResource() throws Exception {
        showTitle("modifyResource");

        // Modify resource description
        UmaResourceResponse resourceStatus = null;
        try {
            UmaResource resource = new UmaResource();
            resource.setName("Photo Album 2");
            resource.setIconUri("http://www.example.com/icons/flower.png");
            resource.setScopes(Arrays.asList("http://photoz.example.com/dev/scopes/view", "http://photoz.example.com/dev/scopes/all"));

            resourceStatus = getResourceService().updateResource("Bearer " + pat.getAccessToken(), this.resourceId, resource);
        } catch (ClientResponseFailure ex) {
            System.err.println(ex.getResponse().getEntity(String.class));
            throw ex;
        }

        assertNotNull(resourceStatus, "Resource status is null");
        this.resourceId = resourceStatus.getId();
        assertNotNull(this.resourceId, "Resource description id is null");
    }

    /**
     * Test non existing UMA resource description modification
     */
    @Test(dependsOnMethods = {"modifyResource"})
    public void modifyNotExistingResource() throws Exception {
        showTitle("modifyNotExistingResource");

        UmaResourceResponse resourceStatus = null;
        try {
            UmaResource resource = new UmaResource();
            resource.setName("Photo Album 3");
            resource.setIconUri("http://www.example.com/icons/flower.png");
            resource.setScopes(Arrays.asList("http://photoz.example.com/dev/scopes/view", "http://photoz.example.com/dev/scopes/all"));

            resourceStatus = getResourceService().updateResource("Bearer " + pat.getAccessToken(), this.resourceId, resource);
        } catch (ClientResponseFailure ex) {
            System.err.println(ex.getResponse().getEntity(String.class));
            assertEquals(ex.getResponse().getStatus(), Response.Status.NOT_FOUND.getStatusCode(), "Unexpected response status");
        }

        assertNull(resourceStatus, "Resource status is not null");
    }

    /**
     * Test UMA resource description modification with invalid PAT
     */
    @Test(dependsOnMethods = {"modifyResource"})
    public void testModifyResourceWithInvalidPat() throws Exception {
        showTitle("testModifyResourceWithInvalidPat");

        UmaResourceResponse resourceStatus = null;
        try {
            UmaResource resource = new UmaResource();
            resource.setName("Photo Album 4");
            resource.setIconUri("http://www.example.com/icons/flower.png");
            resource.setScopes(Arrays.asList("http://photoz.example.com/dev/scopes/view", "http://photoz.example.com/dev/scopes/all"));

            resourceStatus = getResourceService().updateResource("Bearer " + pat.getAccessToken() + "_invalid", this.resourceId + "_invalid", resource);
        } catch (ClientResponseFailure ex) {
            System.err.println(ex.getResponse().getEntity(String.class));
            assertEquals(ex.getResponse().getStatus(), Response.Status.UNAUTHORIZED.getStatusCode(), "Unexpected response status");
        }

        assertNull(resourceStatus, "Resource status is not null");
    }

    /**
     * Get resource
     */
    @Test(dependsOnMethods = {"modifyResource"})
    public void getOneResource() throws Exception {
        showTitle("getOneResource");

        UmaResourceWithId resources = null;
        try {
            resources = getResourceService().getResource("Bearer " + pat.getAccessToken(), this.resourceId);
        } catch (ClientResponseFailure ex) {
            System.err.println(ex.getResponse().getEntity(String.class));
            throw ex;
        }

        assertNotNull(resources, "Resource descriptions is null");
    }

    /**
     * Get resources
     */
    @Test(dependsOnMethods = {"getOneResource"})
    public void getResources() throws Exception {
        showTitle("getResources");

        List<String> resources = null;
        try {
            resources = getResourceService().getResourceList("Bearer " + pat.getAccessToken(), "");
        } catch (ClientResponseFailure ex) {
            System.err.println(ex.getResponse().getEntity(String.class));
            throw ex;
        }

        assertNotNull(resources, "Resources is null");
        assertTrue(resources.contains(this.resourceId), "Resource list doesn't contain added resource");
    }

    /**
     * Delete resource
     */
    @Test(dependsOnMethods = {"getResources"})
    public void deleteResource() throws Exception {
        showTitle("testDeleteResource");

        try {
            getResourceService().deleteResource("Bearer " + pat.getAccessToken(), this.resourceId);
        } catch (ClientResponseFailure ex) {
            System.err.println(ex.getResponse().getEntity(String.class));
            throw ex;
        }
    }
}