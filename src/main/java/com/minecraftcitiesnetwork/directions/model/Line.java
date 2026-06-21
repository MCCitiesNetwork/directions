package com.minecraftcitiesnetwork.directions.model;

import java.util.List;

public record Line(String id, String type, String costModel, List<String> stops) {
}
