package ca.cleanhour.web;

import ca.cleanhour.carbon.CarbonService;
import ca.cleanhour.carbon.Dashboard;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class DashboardController {

    private final CarbonService service;

    public DashboardController(CarbonService service) {
        this.service = service;
    }

    @GetMapping("/dashboard")
    public ResponseEntity<?> dashboard() {
        return service.dashboard()
                .<ResponseEntity<?>>map(d -> ResponseEntity.ok()
                        .cacheControl(CacheControl.maxAge(Duration.ofSeconds(60)).cachePublic())
                        .body(d))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .body(Map.of("status", "loading", "message", "Fetching the first IESO reports, try again in a few seconds.")));
    }
}
