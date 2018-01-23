/*
 * Copyright 2017 Red Hat, Inc. and/or its affiliates.
 *
 * Licensed under the Eclipse Public License version 1.0, available at
 * http://www.eclipse.org/legal/epl-v10.html
 */

package io.fabric8.launcher.addon.ui.booster;

import static io.fabric8.launcher.addon.catalog.BoosterFilters.runsOn;

import java.util.Iterator;
import java.util.Optional;
import java.util.function.Predicate;

import javax.inject.Inject;

import org.jboss.forge.addon.ui.context.UIBuilder;
import org.jboss.forge.addon.ui.context.UIContext;
import org.jboss.forge.addon.ui.context.UIExecutionContext;
import org.jboss.forge.addon.ui.context.UINavigationContext;
import org.jboss.forge.addon.ui.context.UIValidationContext;
import org.jboss.forge.addon.ui.input.UISelectOne;
import org.jboss.forge.addon.ui.metadata.UICommandMetadata;
import org.jboss.forge.addon.ui.metadata.WithAttributes;
import org.jboss.forge.addon.ui.result.NavigationResult;
import org.jboss.forge.addon.ui.result.Result;
import org.jboss.forge.addon.ui.result.Results;
import org.jboss.forge.addon.ui.util.Categories;
import org.jboss.forge.addon.ui.util.Metadata;
import org.jboss.forge.addon.ui.wizard.UIWizardStep;

import io.fabric8.launcher.addon.BoosterCatalogFactory;
import io.fabric8.launcher.addon.catalog.BoosterFilters;
import io.fabric8.launcher.addon.catalog.Mission;
import io.fabric8.launcher.addon.catalog.RhoarBooster;
import io.fabric8.launcher.addon.catalog.Runtime;
import io.fabric8.launcher.service.openshift.api.OpenShiftCluster;
import io.fabric8.launcher.service.openshift.api.OpenShiftClusterRegistry;

/**
 * @author <a href="mailto:ggastald@redhat.com">George Gastaldi</a>
 */
public class ChooseRuntimeStep implements UIWizardStep {
    @Inject
    private BoosterCatalogFactory catalogServiceFactory;

    @Inject
    @WithAttributes(label = "Runtime", required = true)
    private UISelectOne<Runtime> runtime;

    @Inject
    private OpenShiftClusterRegistry clusterRegistry;
    
    @Override
    public void initializeUI(UIBuilder builder) throws Exception {
        UIContext context = builder.getUIContext();
        if (context.getProvider().isGUI()) {
            runtime.setItemLabelConverter(Runtime::getName);
        } else {
            runtime.setItemLabelConverter(Runtime::getId);
        }

        runtime.setValueChoices(() -> {
            DeploymentType deploymentType = (DeploymentType) context.getAttributeMap().get(DeploymentType.class);
            Mission mission = (Mission) context.getAttributeMap().get(Mission.class);
            Predicate<RhoarBooster> filter = x -> true;
            if (deploymentType == DeploymentType.CD) {
                String openShiftCluster = (String) context.getAttributeMap().get("OPENSHIFT_CLUSTER");
                Optional<OpenShiftCluster> cluster = clusterRegistry.findClusterById(openShiftCluster);
                if (cluster.isPresent() && cluster.get().getType() != null) {
                    String clusterType = cluster.get().getType();
                    filter = runsOn(clusterType);
                }
            }
            return catalogServiceFactory.getCatalog(context)
                    .getRuntimes(filter.and(BoosterFilters.missions(mission)));
        });

        runtime.setDefaultValue(() -> {
            Iterator<Runtime> iterator = runtime.getValueChoices().iterator();
            return (iterator.hasNext()) ? iterator.next() : null;
        });

        builder.add(runtime);
    }

    @Override
    public void validate(UIValidationContext context) {
        UIContext uiContext = context.getUIContext();
        Mission mission = (Mission) uiContext.getAttributeMap().get(Mission.class);
        Optional<RhoarBooster> booster = catalogServiceFactory.getCatalog(uiContext)
                .getBooster(BoosterFilters.missions(mission)
                        .and(BoosterFilters.runtimes(runtime.getValue())));
        if (!booster.isPresent()) {
            context.addValidationError(runtime,
                    "No booster found for mission '" + mission + "' and runtime '" + runtime.getValue() + "'");
        }
    }

    @Override
    public UICommandMetadata getMetadata(UIContext context) {
        return Metadata.forCommand(getClass()).name("Runtime")
                .description("Choose the runtime for your mission")
                .category(Categories.create("Fabric8"));
    }

    @Override
    public NavigationResult next(UINavigationContext context) throws Exception {
        context.getUIContext().getAttributeMap().put(Runtime.class, runtime.getValue());
        return null;
    }

    @Override
    public Result execute(UIExecutionContext context) throws Exception {
        return Results.success();
    }

}
