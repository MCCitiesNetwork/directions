package com.minecraftcitiesnetwork.directions.model;

import java.util.List;

public record Line(String id, String type, CostModel costModel, List<String> stops) {
}
