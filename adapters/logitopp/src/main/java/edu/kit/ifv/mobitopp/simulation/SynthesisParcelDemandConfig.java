package edu.kit.ifv.mobitopp.simulation;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;

import edu.kit.ifv.mobitopp.simulation.parcels.ParcelDestinationType;

public record SynthesisParcelDemandConfig(
		double deliveryMean,
		double deliveryStdDev,
		int deliveryMin,
		int deliveryMax,
		Map<ParcelDestinationType, Double> deliveryShares,
		boolean returnsEnabled,
		double returnShare,
		Map<ParcelDestinationType, Double> pickupShares) {

	public static final String HARNESS_CONTRACT = "synthesis-logitopp-adapter-v2";
	public static final String DEMAND_CONTRACT = "synthesis-parcel-demand-v1";

	public static SynthesisParcelDemandConfig load(File file, String harnessContract)
			throws IOException {
		if (!HARNESS_CONTRACT.equals(harnessContract)) {
			throw new IllegalArgumentException("Incompatible harness contract: "
					+ harnessContract + ", expected " + HARNESS_CONTRACT);
		}
		Map<String, String> values = flatten(file);
		requireContract(values);

		double mean = decimal(values, "parcelOrders.perPersonPerWeek.mean");
		double stdDev = decimal(values, "parcelOrders.perPersonPerWeek.stdDev");
		int min = integer(values, "parcelOrders.perPersonPerWeek.min");
		int max = integer(values, "parcelOrders.perPersonPerWeek.max");
		Map<ParcelDestinationType, Double> deliveryShares = new LinkedHashMap<>();
		deliveryShares.put(ParcelDestinationType.HOME, decimal(values, "parcelOrders.destinationShares.HOME"));
		deliveryShares.put(ParcelDestinationType.WORK, decimal(values, "parcelOrders.destinationShares.WORK"));
		deliveryShares.put(ParcelDestinationType.PACK_STATION,
				decimal(values, "parcelOrders.destinationShares.PACKSTATION"));

		boolean returnsEnabled = bool(values, "returns.enabled");
		double returnShare = decimal(values, "returns.shareOfPrivateOrders");
		Map<ParcelDestinationType, Double> pickupShares = new LinkedHashMap<>();
		pickupShares.put(ParcelDestinationType.HOME, decimal(values, "returns.pickupShares.HOME"));
		pickupShares.put(ParcelDestinationType.WORK, decimal(values, "returns.pickupShares.WORK"));

		validate(mean, stdDev, min, max, deliveryShares, returnsEnabled, returnShare,
				pickupShares);
		return new SynthesisParcelDemandConfig(mean, stdDev, min, max,
				deliveryShares, returnsEnabled, returnShare, pickupShares);
	}

	private static Map<String, String> flatten(File file) throws IOException {
		Map<String, String> values = new LinkedHashMap<>();
		Deque<Section> sections = new ArrayDeque<>();
		for (String raw : Files.readAllLines(file.toPath())) {
			if (raw.isBlank() || raw.stripLeading().startsWith("#")) {
				continue;
			}
			String withoutComment = raw.replaceFirst("\\s+#.*$", "");
			if (withoutComment.isBlank()) {
				continue;
			}
			int indent = withoutComment.indexOf(withoutComment.stripLeading());
			String line = withoutComment.trim();
			int colon = line.indexOf(':');
			if (colon < 1) {
				throw new IllegalArgumentException("Invalid parcel demand line: " + raw);
			}
			while (!sections.isEmpty() && indent <= sections.peekLast().indent()) {
				sections.removeLast();
			}
			String key = line.substring(0, colon).trim();
			String value = line.substring(colon + 1).trim();
			if (value.isEmpty()) {
				sections.addLast(new Section(indent, key));
			} else {
				StringBuilder path = new StringBuilder();
				for (Section section : sections) {
					path.append(section.key()).append('.');
				}
				values.put(path + key, unquote(value));
			}
		}
		return values;
	}

	private static void requireContract(Map<String, String> values) {
		String actual = required(values, "contractVersion");
		if (!DEMAND_CONTRACT.equals(actual)) {
			throw new IllegalArgumentException("Incompatible parcel demand contract: "
					+ actual + ", expected " + DEMAND_CONTRACT);
		}
	}

	private static void validate(double mean, double stdDev, int min, int max,
			Map<ParcelDestinationType, Double> deliveryShares,
			boolean returnsEnabled, double returnShare,
			Map<ParcelDestinationType, Double> pickupShares) {
		if (!Double.isFinite(mean) || mean < 0 || !Double.isFinite(stdDev) || stdDev < 0
				|| min < 0 || max < min) {
			throw new IllegalArgumentException("Invalid parcel quantity parameters");
		}
		requireShares("delivery", deliveryShares);
		requireShares("pickup", pickupShares);
		if (!Double.isFinite(returnShare) || returnShare < 0 || returnShare > 1
				|| (returnsEnabled && returnShare <= 0)) {
			throw new IllegalArgumentException("Invalid return share: " + returnShare);
		}
	}

	private static void requireShares(String label,
			Map<ParcelDestinationType, Double> shares) {
		double sum = 0;
		for (double share : shares.values()) {
			if (!Double.isFinite(share) || share < 0) {
				throw new IllegalArgumentException("Invalid " + label + " share: " + share);
			}
			sum += share;
		}
		if (Math.abs(sum - 1.0) > 1e-6) {
			throw new IllegalArgumentException(label + " shares must sum to 1.0, got " + sum);
		}
	}

	private static String required(Map<String, String> values, String key) {
		String value = values.get(key);
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException("Missing parcel demand key: " + key);
		}
		return value;
	}

	private static double decimal(Map<String, String> values, String key) {
		return Double.parseDouble(required(values, key));
	}

	private static int integer(Map<String, String> values, String key) {
		return Integer.parseInt(required(values, key));
	}

	private static boolean bool(Map<String, String> values, String key) {
		String value = required(values, key);
		if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
			throw new IllegalArgumentException("Invalid boolean for " + key + ": " + value);
		}
		return Boolean.parseBoolean(value);
	}

	private static String unquote(String value) {
		if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\""))
				|| (value.startsWith("'") && value.endsWith("'")))) {
			return value.substring(1, value.length() - 1);
		}
		return value;
	}

	private record Section(int indent, String key) {
	}
}
