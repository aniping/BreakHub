package com.ateagents.breakhub.api;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ateagents.breakhub.config.ProductProperties;

@RestController
@RequestMapping("/api/v1/equipment")
public class EquipmentController {

    private final ProductProperties properties;

    public EquipmentController(ProductProperties properties) {
        this.properties = properties;
    }

    @GetMapping
    public Map<String, Object> get() {
        return Map.of(
                "equipment_id", properties.equipment().id(),
                "display_name", properties.equipment().displayName());
    }
}
