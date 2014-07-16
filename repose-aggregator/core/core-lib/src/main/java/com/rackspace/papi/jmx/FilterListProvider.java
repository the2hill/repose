package com.rackspace.papi.jmx;

import com.google.common.base.Optional;
import com.rackspace.papi.commons.config.manager.UpdateListener;
import com.rackspace.papi.domain.ServicePorts;
import com.rackspace.papi.filter.SystemModelInterrogator;
import com.rackspace.papi.model.Filter;
import com.rackspace.papi.model.ReposeCluster;
import com.rackspace.papi.model.SystemModel;
import com.rackspace.papi.service.config.ConfigurationService;
import com.rackspace.papi.service.healthcheck.HealthCheckService;
import com.rackspace.papi.service.healthcheck.HealthCheckServiceHelper;
import com.rackspace.papi.service.healthcheck.Severity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.inject.Inject;
import javax.inject.Named;
import java.util.List;

@Named
public class FilterListProvider {
    
    private static final Logger LOG = LoggerFactory.getLogger(FilterListProvider.class);

    private final ConfigurationService configurationService;
    private final ConfigurationInformation configurationInformation;
    private final ServicePorts servicePorts;
    private final HealthCheckService healthCheckService;

    private String healthCheckUid;

    private SystemModelListener systemModelListener;
    public static final String SYSTEM_MODEL_CONFIG_HEALTH_REPORT = "SystemModelConfigError";
    private HealthCheckServiceHelper healthCheckServiceHelper;

    @Inject
    public FilterListProvider(@Qualifier("servicePorts") ServicePorts servicePorts,
                                ConfigurationService configurationService,
                              ConfigurationInformation configurationInformation,
                              HealthCheckService healthCheckService) {
        this.configurationService = configurationService;
        this.configurationInformation = configurationInformation;
        this.servicePorts =servicePorts;
        this.healthCheckService = healthCheckService;
    }

    @PostConstruct
    public void afterPropertiesSet() {
        systemModelListener = new SystemModelListener();
        configurationService.subscribeTo("system-model.cfg.xml", systemModelListener, SystemModel.class);

        healthCheckServiceHelper = new HealthCheckServiceHelper(healthCheckService, LOG, healthCheckUid);
        healthCheckUid = healthCheckService.register(FilterListProvider.class);
    }

    @PreDestroy
    public void destroy() {
        configurationService.unsubscribeFrom("system-model.cfg.xml", systemModelListener);
    }


    private class SystemModelListener implements UpdateListener<SystemModel> {

        private boolean initialized = false;

        /**
         * Whenever the system-model is updated get a list of all the filters that are configured.
         * @param systemModel
         */
        @Override
        public void configurationUpdated(SystemModel systemModel) {
            LOG.info("System model updated");
            initialized = false;

            List<FilterInformation> filterList = configurationInformation.getFilterList();

            SystemModelInterrogator interrogator = new SystemModelInterrogator(servicePorts);
            Optional<ReposeCluster> cluster = interrogator.getLocalCluster(systemModel);

            if (cluster.isPresent()) {
                synchronized (filterList) {
                    filterList.clear();

                    if (cluster.get().getFilters() != null && cluster.get().getFilters().getFilter() != null) {
                        for (Filter filter : cluster.get().getFilters().getFilter()) {
                            filterList.add(new FilterInformation(filter.getId(), filter.getName(), filter.getUriRegex(),
                                    filter.getConfiguration(), false));
                        }
                    }
                }

                initialized = true;

                healthCheckServiceHelper.resolveIssue(SYSTEM_MODEL_CONFIG_HEALTH_REPORT);
            } else {
                LOG.error("Unable to identify the local host in the system model - please check your system-model.cfg.xml");
                healthCheckServiceHelper.reportIssue(SYSTEM_MODEL_CONFIG_HEALTH_REPORT, "Unable to identify the " +
                        "local host in the system model - please check your system-model.cfg.xml", Severity.BROKEN);
            }
        }

        @Override
        public boolean isInitialized() {
            return initialized;
        }
    }

}
