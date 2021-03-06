/*
 * oxAuth is available under the MIT License (2008). See http://opensource.org/licenses/MIT for full text.
 *
 * Copyright (c) 2014, Gluu
 */

package org.xdi.oxauth.service.external;

import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.ejb.DependsOn;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Named;

import org.xdi.model.SimpleCustomProperty;
import org.xdi.model.custom.script.CustomScriptType;
import org.xdi.model.custom.script.conf.CustomScriptConfiguration;
import org.xdi.model.custom.script.type.scope.DynamicScopeType;
import org.xdi.oxauth.service.external.context.DynamicScopeExternalContext;
import org.xdi.service.custom.script.ExternalScriptService;

import com.google.common.collect.Sets;

/**
 * Provides factory methods needed to create dynamic scope extension
 *
 * @author Yuriy Movchan Date: 01/08/2015
 */
@ApplicationScoped
@DependsOn("appInitializer")
@Named
public class ExternalDynamicScopeService extends ExternalScriptService {

	private static final long serialVersionUID = 1416361273036208685L;

	public ExternalDynamicScopeService() {
		super(CustomScriptType.DYNAMIC_SCOPE);
	}

	public boolean executeExternalUpdateMethod(CustomScriptConfiguration customScriptConfiguration, DynamicScopeExternalContext dynamicScopeContext) {
		try {
			log.debug("Executing python 'update' method");
			DynamicScopeType dynamicScopeType = (DynamicScopeType) customScriptConfiguration.getExternalType();
			Map<String, SimpleCustomProperty> configurationAttributes = customScriptConfiguration.getConfigurationAttributes();
			return dynamicScopeType.update(dynamicScopeContext, configurationAttributes);
		} catch (Exception ex) {
			log.error(ex.getMessage(), ex);
		}
		
		return false;
	}

	private Set<CustomScriptConfiguration> getScriptsToExecute(DynamicScopeExternalContext context) {
        Set<String> allowedScripts = Sets.newHashSet();
		for (org.xdi.oxauth.model.common.Scope scope : context.getScopes()) {
			List<String> scopeScripts = scope.getDynamicScopeScripts();
			if (scopeScripts != null) { 
				allowedScripts.addAll(scopeScripts);
			}
		}

		Set<CustomScriptConfiguration> result = Sets.newHashSet();
		for (CustomScriptConfiguration script : this.customScriptConfigurations) {
			if (allowedScripts.contains(script.getCustomScript().getDn())) {
				result.add(script);
			}
		}
		return result;
	}

	public boolean executeExternalUpdateMethods(DynamicScopeExternalContext dynamicScopeContext) {
		boolean result = true;
		for (CustomScriptConfiguration customScriptConfiguration : getScriptsToExecute(dynamicScopeContext)) {
			result &= executeExternalUpdateMethod(customScriptConfiguration, dynamicScopeContext);
			if (!result) {
				return result;
			}
		}

		return result;
	}

}
