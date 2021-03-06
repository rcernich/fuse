package io.fabric8.forge.rest;

import io.fabric8.forge.rest.dto.CommandInfoDTO;
import io.fabric8.forge.rest.dto.CommandInputDTO;
import io.fabric8.forge.rest.dto.ExecutionRequest;
import io.fabric8.forge.rest.dto.ExecutionResult;
import io.fabric8.forge.rest.dto.UICommands;
import io.fabric8.forge.rest.dto.ValidationResult;
import io.fabric8.forge.rest.dto.WizardResultsDTO;
import io.fabric8.forge.rest.hooks.CommandCompletePostProcessor;
import io.fabric8.forge.rest.ui.RestUIContext;
import io.fabric8.forge.rest.ui.RestUIRuntime;
import io.fabric8.utils.Strings;
import org.jboss.forge.addon.convert.ConverterFactory;
import org.jboss.forge.addon.resource.Resource;
import org.jboss.forge.addon.resource.ResourceFactory;
import org.jboss.forge.addon.ui.command.CommandFactory;
import org.jboss.forge.addon.ui.command.UICommand;
import org.jboss.forge.addon.ui.controller.CommandController;
import org.jboss.forge.addon.ui.controller.CommandControllerFactory;
import org.jboss.forge.addon.ui.controller.WizardCommandController;
import org.jboss.forge.addon.ui.output.UIMessage;
import org.jboss.forge.addon.ui.result.Result;
import org.jboss.forge.furnace.Furnace;
import org.jboss.forge.furnace.addons.AddonRegistry;
import org.jboss.forge.furnace.services.Imported;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ejb.Stateless;
import javax.inject.Inject;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Path("/api/forge")
@Stateless
public class CommandsResource {
    private static final transient Logger LOG = LoggerFactory.getLogger(CommandsResource.class);

    @Inject
    private Furnace furnace;

    @Inject
    private CommandControllerFactory commandControllerFactory;

    @Inject
    private CommandFactory commandFactory;

    @Inject
    private CommandCompletePostProcessor commandCompletePostProcessor;

    private ConverterFactory converterFactory;

    @GET
    public String getInfo() {
        return furnace.getVersion().toString();
    }

    @GET
    @Path("/commandNames")
    @Produces(MediaType.APPLICATION_JSON)
    public List<String> getCommandNames() {
        List<String> answer = new ArrayList<>();
        try (RestUIContext context = new RestUIContext()) {
            for (String commandName : commandFactory.getCommandNames(context)) {
                answer.add(commandName);
            }
        }
        return answer;
    }

    @GET
    @Path("/commands")
    @Produces(MediaType.APPLICATION_JSON)
    public List<CommandInfoDTO> getCommands() {
        return getCommands(null);
    }

    @GET
    @Path("/commands/{path: .*}")
    @Produces(MediaType.APPLICATION_JSON)
    public List<CommandInfoDTO> getCommands(@PathParam("path") String resourcePath) {
        List<CommandInfoDTO> answer = new ArrayList<>();
        try (RestUIContext context = createUIContext(resourcePath)) {
            for (String name : commandFactory.getCommandNames(context)) {
                try {
                    CommandInfoDTO dto = createCommandInfoDTO(context, name);
                    if (dto != null && dto.isEnabled()) {
                        answer.add(dto);
                    }
                } catch (Exception e) {
                    LOG.warn("Ignored exception on command " + name + " probably due to missing project?: " + e, e);
                }
            }
        }
        return answer;
    }

    @GET
    @Path("/command/{name}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getCommandInfo(@PathParam("name") String name) {
        return getCommandInfo(name, null);
    }

    @GET
    @Path("/command/{name}/{path: .*}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getCommandInfo(@PathParam("name") String name, @PathParam("path") String resourcePath) {
        CommandInfoDTO answer = null;
        try (RestUIContext context = createUIContext(resourcePath)) {
            answer = createCommandInfoDTO(context, name);
        }
        if (answer != null) {
            return Response.ok(answer).build();
        } else {
            return Response.status(Status.NOT_FOUND).build();
        }
    }

    @GET
    @Path("/commandInput/{name}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getCommandInput(@PathParam("name") String name) throws Exception {
        return getCommandInput(name, null);
    }

    @GET
    @Path("/commandInput/{name}/{path: .*}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getCommandInput(@PathParam("name") String name, @PathParam("path") String resourcePath) throws Exception {
        try {
            CommandInputDTO answer = null;
            try (RestUIContext context = createUIContext(resourcePath)) {
                UICommand command = getCommandByName(context, name);
                if (command != null) {
                    CommandController controller = createController(context, command);
                    answer = UICommands.createCommandInputDTO(context, command, controller);
                }
                if (answer != null) {
                    return Response.ok(answer).build();
                } else {
                    return Response.status(Status.NOT_FOUND).build();
                }
            }
        } catch (Throwable e) {
            LOG.warn("Failed to find input for command " + name + ". " + e, e);
            throw e;
        }
    }


    @POST
    @Path("/command/execute/{name}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response executeCommand(@PathParam("name") String name, ExecutionRequest executionRequest) throws Exception {
        try {
            String resourcePath = executionRequest.getResource();
            try (RestUIContext context = createUIContext(resourcePath)) {
                UICommand command = getCommandByName(context, name);
                if (command == null) {
                    return Response.status(Status.NOT_FOUND).build();
                }
                List<Map<String, String>> inputList = executionRequest.getInputList();
                CommandController controller = createController(context, command);
                ExecutionResult answer = null;
                if (controller instanceof WizardCommandController) {
                    WizardCommandController wizardCommandController = (WizardCommandController) controller;
                    List<WizardCommandController> controllers = new ArrayList<>();
                    List<CommandInputDTO> stepPropertiesList = new ArrayList<>();
                    List<ExecutionResult> stepResultList = new ArrayList<>();
                    List<ValidationResult> stepValidationList = new ArrayList<>();
                    controllers.add(wizardCommandController);
                    WizardCommandController lastController = wizardCommandController;
                    Result lastResult = null;
                    int page = executionRequest.wizardStep();
                    int nextPage = page + 1;
                    boolean canMoveToNextStep = false;
                    for (Map<String, String> inputs : inputList) {
                        UICommands.populateController(inputs, lastController, getConverterFactory());
                        List<UIMessage> messages = lastController.validate();
                        ValidationResult stepValidation = UICommands.createValidationResult(context, lastController, messages);
                        stepValidationList.add(stepValidation);
                        if (!stepValidation.isValid()) {
                            break;
                        }
                        canMoveToNextStep = lastController.canMoveToNextStep();
                        boolean valid = lastController.isValid();
                        if (!canMoveToNextStep) {
                            // lets assume we can execute now
                            lastResult = lastController.execute();
                            LOG.debug("Invoked command " + name + " with " + executionRequest + " result: " + lastResult);
                            ExecutionResult stepResults = UICommands.createExecutionResult(context, lastResult, false);
                            stepResultList.add(stepResults);
                            break;
                        } else if (!valid) {
                            LOG.warn("Cannot move to next step as invalid despite the validation saying otherwise");
                            break;
                        }
                        WizardCommandController nextController = lastController.next();
                        if (nextController != null) {
                            if (nextController == lastController) {
                                LOG.warn("No idea whats going on ;)");
                                break;
                            }
                            lastController = nextController;
                            lastController.initialize();
                            controllers.add(lastController);
                            CommandInputDTO stepDto = UICommands.createCommandInputDTO(context, command, lastController);
                            stepPropertiesList.add(stepDto);
                        } else {
                            int i = 0;
                            for (WizardCommandController stepController : controllers) {
                                Map<String, String> stepControllerInputs = inputList.get(i++);
                                UICommands.populateController(stepControllerInputs, stepController, getConverterFactory());
                                lastResult = stepController.execute();
                                LOG.debug("Invoked command " + name + " with " + executionRequest + " result: " + lastResult);
                                ExecutionResult stepResults = UICommands.createExecutionResult(context, lastResult, false);
                                stepResultList.add(stepResults);
                            }
                            break;
                        }
                    }
                    answer = UICommands.createExecutionResult(context, lastResult, canMoveToNextStep);
                    WizardResultsDTO wizardResultsDTO = new WizardResultsDTO(stepPropertiesList, stepValidationList, stepResultList);
                    answer.setWizardResults(wizardResultsDTO);
                } else {
                    Map<String, String> inputs = inputList.get(0);
                    UICommands.populateController(inputs, controller, getConverterFactory());
                    Result result = controller.execute();
                    LOG.debug("Invoked command " + name + " with " + executionRequest + " result: " + result);
                    answer = UICommands.createExecutionResult(context, result, false);
                }
                if (answer.isCommandCompleted() && commandCompletePostProcessor != null) {
                    commandCompletePostProcessor.firePostCompleteActions(name, executionRequest, context, controller, answer);
                }
                return Response.ok(answer).build();
            }
        } catch (Throwable e) {
            LOG.warn("Failed to invoke command " + name + " on " + executionRequest + ". " + e, e);
            throw e;
        }
    }



    @POST
    @Path("/command/validate/{name}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response validateCommand(@PathParam("name") String name, ExecutionRequest executionRequest) throws Exception {
        try {
            String resourcePath = executionRequest.getResource();
            try (RestUIContext context = createUIContext(resourcePath)) {
                UICommand command = getCommandByName(context, name);
                if (command == null) {
                    return Response.status(Status.NOT_FOUND).build();
                }
                List<Map<String, String>> inputList = executionRequest.getInputList();
                CommandController controller = createController(context, command);
                ValidationResult answer = null;
                if (controller instanceof WizardCommandController) {
                    WizardCommandController wizardCommandController = (WizardCommandController) controller;
                    List<WizardCommandController> controllers = new ArrayList<>();
                    List<CommandInputDTO> stepPropertiesList = new ArrayList<>();
                    List<ValidationResult> stepResultList = new ArrayList<>();
                    List<ValidationResult> stepValidationList = new ArrayList<>();
                    controllers.add(wizardCommandController);
                    WizardCommandController lastController = wizardCommandController;
                    List<UIMessage> lastResult = null;
                    int page = executionRequest.wizardStep();
                    int nextPage = page + 1;
                    boolean canMoveToNextStep = false;
                    for (Map<String, String> inputs : inputList) {
                        UICommands.populateController(inputs, lastController, getConverterFactory());
                        CommandInputDTO stepDto = UICommands.createCommandInputDTO(context, command, lastController);
                        stepPropertiesList.add(stepDto);
                        canMoveToNextStep = lastController.canMoveToNextStep();
                        boolean valid = lastController.isValid();
                        if (!canMoveToNextStep) {
                            // lets assume we can execute now
                            lastResult = lastController.validate();
                            LOG.debug("Invoked command " + name + " with " + executionRequest + " result: " + lastResult);
                            ValidationResult stepResults = UICommands.createValidationResult(context, controller, lastResult);
                            stepResultList.add(stepResults);
                            break;
                        } else if (!valid) {
                            LOG.warn("Cannot move to next step as invalid despite the validation saying otherwise");
                            break;
                        }
                        WizardCommandController nextController = lastController.next();
                        if (nextController != null) {
                            if (nextController == lastController) {
                                LOG.warn("No idea whats going on ;)");
                                break;
                            }
                            lastController = nextController;
                            lastController.initialize();
                            controllers.add(lastController);
                        } else {
                            int i = 0;
                            for (WizardCommandController stepController : controllers) {
                                Map<String, String> stepControllerInputs = inputList.get(i++);
                                UICommands.populateController(stepControllerInputs, stepController, getConverterFactory());
                                lastResult = stepController.validate();
                                LOG.debug("Invoked command " + name + " with " + executionRequest + " result: " + lastResult);
                                ValidationResult stepResults = UICommands.createValidationResult(context, controller, lastResult);
                                stepResultList.add(stepResults);
                            }
                            break;
                        }
                    }
                    answer = UICommands.createValidationResult(context, controller, lastResult);
                    // TODO do we need stepValidationList?
                    //WizardResultsDTO wizardResultsDTO = new WizardResultsDTO(stepPropertiesList, stepValidationList, stepResultList);
                    WizardResultsDTO wizardResultsDTO = new WizardResultsDTO(stepPropertiesList, stepResultList, new ArrayList<ExecutionResult>());
                    answer.setWizardResults(wizardResultsDTO);
                } else {
                    Map<String, String> inputs = inputList.get(0);
                    UICommands.populateController(inputs, controller, getConverterFactory());
                    List<UIMessage> result = controller.validate();
                    LOG.debug("Invoked command " + name + " with " + executionRequest + " result: " + result);
                    answer = UICommands.createValidationResult(context, controller, result);
                }
                return Response.ok(answer).build();
            }
        } catch (Throwable e) {
            LOG.warn("Failed to invoke command " + name + " on " + executionRequest + ". " + e, e);
            throw e;
        }
    }

    protected CommandInfoDTO createCommandInfoDTO(RestUIContext context, String name) {
        UICommand command = getCommandByName(context, name);
        CommandInfoDTO answer = null;
        if (command != null) {
            answer = UICommands.createCommandInfoDTO(context, command);
        }
        return answer;
    }

    protected UICommand getCommandByName(RestUIContext context, String name) {
        return commandFactory.getCommandByName(context, name);
    }

    protected CommandController createController(RestUIContext context, UICommand command) throws Exception {
        RestUIRuntime runtime = new RestUIRuntime();
        CommandController controller = commandControllerFactory.createController(context, runtime,
                command);
        controller.initialize();
        return controller;
    }

    protected RestUIContext createUIContext(String resourcePath) {
        AddonRegistry addonRegistry = furnace.getAddonRegistry();
        Imported<ResourceFactory> resourceFactoryImport = addonRegistry.getServices(ResourceFactory.class);
        ResourceFactory resourceFactory = resourceFactoryImport.get();
        Resource<?> selection = null;
        if (Strings.isNotBlank(resourcePath) && resourceFactory != null) {
            File file = new File(resourcePath);
            if (!file.exists() && !resourcePath.startsWith("/")) {
                resourcePath = "/" + resourcePath;
                file = new File(resourcePath);
            }
            if (file.exists()) {
                selection = resourceFactory.create(file);
            } else {
                selection = resourceFactory.create(resourcePath);
            }
        }
        return new RestUIContext(selection);
    }

    public ConverterFactory getConverterFactory() {
        if (converterFactory == null) {
            AddonRegistry addonRegistry = furnace.getAddonRegistry();
            Imported<ConverterFactory> converterFactoryImport = addonRegistry.getServices(ConverterFactory.class);
            converterFactory = converterFactoryImport.get();
        }
        return converterFactory;
    }

    public void setConverterFactory(ConverterFactory converterFactory) {
        this.converterFactory = converterFactory;
    }
}
