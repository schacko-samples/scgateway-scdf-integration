package com.example.scdfrestfacade;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

@SpringBootApplication
@RestController
@RequestMapping("/scdf-facade")
public class ScdfRestFacadeApplication {

	private Map<UUID, String> requestMap = new HashMap<>();

	private List<String> streams = new ArrayList<>();

	private Map<String, Boolean> deploymentStatusInfo = new HashMap<>();

	public static void main(String[] args) {
		SpringApplication.run(ScdfRestFacadeApplication.class, args);
	}

	@PostMapping("/publish")
	public String publishData(@RequestParam String data) {

		RestTemplate restTemplate = new RestTemplate();

		MultiValueMap<String, Object> uriVariables = new LinkedMultiValueMap<>();
		uriVariables.add("name", "publish-to-kafka-log");
		uriVariables.add("definition", "http | transform --spel.function.expression=payload.toUpperCase() | log");

		try {
			final UUID uuid = UUID.randomUUID();
			requestMap.put(uuid, "Deployment status: " + Boolean.FALSE.toString());

			if (!streams.contains("publish-to-kafka-log")) {
				streams.add("publish-to-kafka-log");

				//restTemplate.postForObject("http://192.168.99.97/streams/definitions/", uriVariables, String.class);
				restTemplate.postForObject("http://scdf-server.default.svc.cluster.local/streams/definitions/", uriVariables, String.class);

				//restTemplate.postForObject("http://192.168.99.97/streams/deployments/publish-to-kafka-log", new HashMap<String, String>(), Object.class);
				restTemplate.postForObject("http://scdf-server.default.svc.cluster.local/streams/deployments/publish-to-kafka-log", new HashMap<String, String>(), Object.class);
				deploymentStatusInfo.put("publish-to-kafka-log", Boolean.FALSE);
				asyncPublish(data, uuid);

				return "The infrastructure necessary for publishing your data is being prepared at the moment. Once it is done," +
						"your data will be published. In the meantime, you can check this transaction id to check the status on the deployment: " +
						uuid.toString() + " Use the REST endpoint /status?transactionId=<transaction-id> for finding the status.";
			}
			else if (streams.contains("publish-to-kafka-log") && deploymentStatusInfo.get("publish-to-kafka-log").equals(Boolean.FALSE)) {
				asyncPublish(data, uuid);

				return "Check the status on the publishing using the tranaction ID: " +
						uuid.toString() + " Use the REST endpoint /status?transactionId=<transaction-id> for finding the status.";
			}
			else {
				publishReceivedData(uuid, data);
				return requestMap.get(uuid);
			}
		}
		catch (Exception e) {
			return "Something went wrong " + e;
		}
	}

	private void asyncPublish(String data, UUID uuid) {
		CompletableFuture<Boolean> f = CompletableFuture.supplyAsync(() -> {
			try {
				return waitForDeploymentToComplete(uuid);
			}
			catch (InterruptedException e) {
				e.printStackTrace();
			}
			return null;
		});
		f.thenAccept(b -> publishReceivedData(uuid, data));
	}

	public void publishReceivedData(UUID uuid, String data) {
		RestTemplate restTemplate = new RestTemplate();

		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.TEXT_PLAIN);

		HttpEntity<String> request = new HttpEntity<>(data, headers);

		final ResponseEntity<Object> objectResponseEntity = restTemplate.postForEntity("http://publish-to-kafka-log-http.default.svc.cluster.local:8080", request, Object.class);

		requestMap.put(uuid, "The publish request status: " + objectResponseEntity.getStatusCode().is2xxSuccessful() + ": " + objectResponseEntity.getStatusCodeValue());
	}

	public boolean waitForDeploymentToComplete(UUID uuid) throws InterruptedException {
		boolean deployed = false;
		RestTemplate restTemplate = new RestTemplate();
		while (!deployed) {
			final String response = restTemplate.getForObject("http://scdf-server.default.svc.cluster.local/streams/deployments/publish-to-kafka-log", String.class);
			System.out.println("Transaction ID: " + uuid);
			System.out.println("Response received: " + response);
			if (response.contains("\"status\":\"deployed\"")) {
				System.out.println("Status is deployed!!");
				deployed = true;
				requestMap.put(uuid, "Deployment status: " + Boolean.TRUE.toString());
				deploymentStatusInfo.put("publish-to-kafka-log", Boolean.TRUE);
			}
			else if (response.contains("\"status\":\"deploying\"")) {
				System.out.println("Status is deploying!!");
			}
			Thread.sleep(2000);
		}
		return deployed;
	}

	@GetMapping("/status")
	public String checkStatus(@RequestParam String transactionId) {
		final UUID uuid = UUID.fromString(transactionId);
		return this.requestMap.get(uuid);
	}
}
