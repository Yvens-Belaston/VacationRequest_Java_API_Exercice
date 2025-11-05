package com.bonitasoft.VacationRequest.service;

import org.bonitasoft.engine.api.APIClient;
import org.bonitasoft.engine.api.ApiAccessType;
import org.bonitasoft.engine.api.IdentityAPI;
import org.bonitasoft.engine.api.ProcessAPI;
import org.bonitasoft.engine.bpm.contract.ContractViolationException;
import org.bonitasoft.engine.bpm.flownode.ActivityInstanceCriterion;
import org.bonitasoft.engine.bpm.flownode.HumanTaskInstance;
import org.bonitasoft.engine.bpm.process.ProcessDefinition;
import org.bonitasoft.engine.bpm.process.ProcessDeploymentInfo;
import org.bonitasoft.engine.bpm.process.ProcessInstance;
import org.bonitasoft.engine.exception.BonitaException;
import org.bonitasoft.engine.identity.User;
import org.bonitasoft.engine.search.SearchOptionsBuilder;
import org.bonitasoft.engine.search.SearchResult;
import org.bonitasoft.engine.util.APITypeManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.time.LocalDate;
import java.util.*;

/**
 * Service class to interact with Bonita BPM for the "New Vacation Request"
 * process.
 * This class can be run standalone with Maven using: 
 * mvn exec:java -Dexec.mainClass="com.bonitasoft.VacationRequest.service.VacationRequestService"
 */
public class VacationRequestService {

    private static final Logger logger = LoggerFactory.getLogger(VacationRequestService.class);

    private APIClient apiClient;
    private ProcessAPI processAPI;
    private IdentityAPI identityAPI;

    private static final String PROCESS_NAME = "New Vacation Request";
    private static final String REVIEW_TASK_NAME = "Review request";

    /**
     * Constructor initializes the Bonita API client and logs in.
     */
    public VacationRequestService() {
        this.apiClient = new APIClient();
    }

    /**
     * Login to Bonita server with credentials from properties file or defaults.
     */
    public void login() throws BonitaException, IOException {
        Properties properties = loadProperties();
        String serverUrl = properties.getProperty("bonita.server.url", "http://localhost:8080");
        String username = properties.getProperty("bonita.username", "walter.bates");
        String password = properties.getProperty("bonita.password", "bpm");

        logger.info("Connecting to Bonita server at: {}", serverUrl);
        HashMap<String, String> settings = new HashMap<String, String>();
        settings.put("server.url", serverUrl);
        settings.put("application.name", "bonita");
        settings.put("basicAuthentication.active", "true");
        settings.put("basicAuthentication.username", "http-api");
        settings.put("basicAuthentication.password", "h11p-@p1");

        APITypeManager.setAPITypeAndParams((ApiAccessType.HTTP), settings);
        apiClient.login(username, password);
        processAPI = apiClient.getProcessAPI();
        identityAPI = apiClient.getIdentityAPI();

        logger.info("Successfully logged in as user: {}", username);
    }

    /**
     * Logout from Bonita server.
     */
    public void logout() throws BonitaException {
        if (apiClient != null) {
            apiClient.logout();
            logger.info("Successfully logged out from Bonita server");
        }
    }

    /**
     * Load properties from application.properties file.
     */
    private Properties loadProperties() throws IOException {
        Properties properties = new Properties();
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("application.properties")) {
            if (input != null) {
                properties.load(input);
            }
        }
        return properties;
    }

    /**
     * Instantiate a new "New Vacation Request" process with contract data.
     *
     * @param startDate    The start date of the vacation (Date Only)
     * @param returnDate   The return date of the vacation (Date Only)
     * @param numberOfDays The number of vacation days requested
     * @return ProcessInstance the created process instance
     * @throws BonitaException if process instantiation fails
     */
    public ProcessInstance instantiateVacationRequest(LocalDate startDate, LocalDate returnDate, Integer numberOfDays)
            throws BonitaException {

        logger.info("Starting instantiation of '{}' process", PROCESS_NAME);

        // Find the process definition by name
        ProcessDefinition processDefinition = getProcessDefinitionByName(PROCESS_NAME);

        if (processDefinition == null) {
            throw new IllegalStateException("Process definition '" + PROCESS_NAME + "' not found");
        }

        logger.info("Found process definition: {} (version: {}) and processId: {}",
                processDefinition.getName(), processDefinition.getVersion(), processDefinition.getId());

        // Build contract inputs
        Map<String, Serializable> contractInputs = new HashMap<>();
        contractInputs.put("startDateContract", startDate);
        contractInputs.put("returnDateContract", returnDate);
        contractInputs.put("numberOfDaysContract", numberOfDays);

        logger.info("Contract inputs - Start Date: {}, Return Date: {}, Number of Days: {}",
                startDate, returnDate, numberOfDays);

        try {
            // Instantiate process with contract
            ProcessInstance processInstance = processAPI.startProcessWithInputs(
                    processDefinition.getId(),
                    contractInputs);

            logger.info("Successfully instantiated process. Process Instance ID: {}",
                    processInstance.getId());

            return processInstance;

        } catch (ContractViolationException e) {
            logger.error("Contract violation while instantiating process: {}", e.getMessage());
            throw e;
        }
    }

    /**
     * Get the manager of the user who initiated the process.
     *
     * @param processInstanceId The ID of the process instance
     * @return User the manager user, or null if no manager found
     * @throws BonitaException if user retrieval fails
     */
    public User getProcessInitiatorManager(Long processInstanceId) throws BonitaException {
        logger.info("Getting manager for process instance: {}", processInstanceId);

        // Get the process instance
        ProcessInstance processInstance = processAPI.getProcessInstance(processInstanceId);

        // Get the user who started the process
        Long startedBy = processInstance.getStartedBy();
        logger.info("Process was started by user ID: {}", startedBy);

        // Get the user
        User initiator = identityAPI.getUser(startedBy);
        logger.info("Process initiator: {} {}", initiator.getFirstName(), initiator.getLastName());

        // Get the manager of the initiator
        Long managerId = initiator.getManagerUserId();

        if (managerId == null || managerId == 0) {
            logger.warn("No manager found for user: {} {}", initiator.getFirstName(), initiator.getLastName());
            return null;
        }

        User manager = identityAPI.getUser(managerId);
        logger.info("Manager found: {} {} (ID: {})",
                manager.getFirstName(), manager.getLastName(), manager.getId());

        return manager;
    }

    /**
     * List available instances of the "Review request" task and execute one with
     * contract data.
     *
     * @param status   The status: "approved" or "rejected"
     * @param comments Additional comments
     * @throws BonitaException if task execution fails
     */
    public void listAndExecuteReviewTask(String status, String comments) throws BonitaException {
        logger.info("Searching for available '{}' task instances", REVIEW_TASK_NAME);

        // Search for pending human tasks with the name "Review request"

        Long userId = apiClient.getSession().getUserId();

        List<HumanTaskInstance> pendingTasks = processAPI.getAssignedHumanTaskInstances(userId, 0, 20,
                ActivityInstanceCriterion.PRIORITY_ASC);

        logger.info("@@@@@@@@@@@Found {} available '{}' task(s) for userId {}", pendingTasks.size(), REVIEW_TASK_NAME, userId);

        if (pendingTasks.isEmpty()) {
            logger.warn("No available '{}' tasks found for current user",
                    REVIEW_TASK_NAME);
            return;
        }

        // List all available tasks
        for (int i = 0; i < pendingTasks.size(); i++) {
            HumanTaskInstance task = pendingTasks.get(i);
            logger.info("Task #{}: ID={}, Name={}, Process Instance ID={}, State={}",
                    i + 1,
                    task.getId(),
                    task.getName(),
                    task.getParentProcessInstanceId(),
                    task.getState());
        }

        // Execute the first available task
        HumanTaskInstance taskToExecute = pendingTasks.get(0);
        executeReviewTask(taskToExecute.getId(), status, comments);
    }

    /**
     * Execute a specific "Review request" task with contract inputs.
     *
     * @param taskId   The ID of the task to execute
     * @param status   The status: "approved" or "rejected"
     * @param comments Additional comments
     * @throws BonitaException if task execution fails
     */
    public void executeReviewTask(Long taskId, String status, String comments) throws BonitaException {
        logger.info("Executing task ID: {} with status='{}' and comments='{}'", taskId, status, comments);

        // Validate status
        if (!status.equals("approved") && !status.equals("rejected")) {
            throw new IllegalArgumentException("Status must be either 'approved' or 'rejected'");
        }

        // Build contract inputs for the task
        Map<String, Serializable> taskContractInputs = new HashMap<>();
        taskContractInputs.put("statusContract", status);
        taskContractInputs.put("commentsContract", comments);

        try {
            // Execute the task with contract inputs
            // The actor filtor defined on the "Review request" assign the task to the user so we just need to exectute the task
            processAPI.executeUserTask(taskId, taskContractInputs);
            
            // If the task was not assigned and we just had candidates
            // processAPI.assignAndExecuteUserTask(apiClient.getSession().getUserId(), taskId, taskContractInputs);

            logger.info("Successfully executed task ID: {}", taskId);

        } catch (ContractViolationException e) {
            logger.error("Contract violation while executing task: {}", e.getMessage());
            throw e;
        }
    }

    /**
     * Helper method to find a process definition by name.
     * Returns the latest version if multiple versions exist.
     */
    private ProcessDefinition getProcessDefinitionByName(String processName) throws BonitaException {
        SearchOptionsBuilder searchBuilder = new SearchOptionsBuilder(0, 1);
        searchBuilder.filter("name", processName);
        searchBuilder.sort("version", org.bonitasoft.engine.search.Order.DESC);

        SearchResult<ProcessDeploymentInfo> searchResult = processAPI
                .searchProcessDeploymentInfos(searchBuilder.done());

        if (searchResult.getCount() > 0) {
            long processDefinitionId = searchResult.getResult().get(0).getProcessId();
            return processAPI.getProcessDefinition(processDefinitionId);
        }

        return null;
    }

    /**
     * Main method to demonstrate the usage of this service.
     * Run with: mvn exec:java -Dexec.mainClass="com.bonitasoft.VacationRequest.service.VacationRequestService"
     */
    public static void main(String[] args) {
        VacationRequestService service = new VacationRequestService();

        try {
            // Login to Bonita
            service.login();

            // Step 1: Instantiate a new vacation request
            LocalDate startDate = LocalDate.of(2025, 12, 20);
            LocalDate returnDate = LocalDate.of(2025, 12, 27);
            Integer numberOfDays = 5;

            // System.out.println("\n=== Creating New Vacation Request ===");
            ProcessInstance processInstance =
            service.instantiateVacationRequest(startDate, returnDate, numberOfDays);
            System.out.println("Created Process Instance ID: " +
            processInstance.getId());

            // System.out.println("\n=== Getting Process Initiator's Manager ===");
            User manager = service.getProcessInitiatorManager(processInstance.getId());
            if (manager != null) {
            System.out.println("Manager: " + manager.getFirstName() + " " +
            manager.getLastName());
            } else {
            System.out.println("No manager found for the process initiator");
            }

            // Step 2: List and execute review task (commented out by default)
            // Uncomment the following lines when a review task is available
            // Change the logged-in user by helen.kelly in application.properties file

            // System.out.println("\n=== Listing and Executing Review Task ===");
            // service.listAndExecuteReviewTask("approved", "Looks good, approved!");

        } catch (BonitaException e) {
            logger.error("Bonita exception occurred: {}", e.getMessage(), e);
            System.err.println("Error: " + e.getMessage());
        } catch (IOException e) {
            logger.error("IO exception occurred: {}", e.getMessage(), e);
            System.err.println("Error reading properties: " + e.getMessage());
        } finally {
            // Logout
            try {
                service.logout();
            } catch (BonitaException e) {
                logger.error("Error during logout: {}", e.getMessage());
            }
        }
    }
}
