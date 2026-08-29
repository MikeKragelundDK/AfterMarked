package dk.aftermarked.tenancy;

import java.util.Objects;
import java.util.UUID;

/**
 * Operatørens id — kunden, eventfirmaet. Adskiller tenants fra hinanden og
 * håndhæves af row-level security i Postgres.
 *
 * Ikke at forveksle med arrangøren ({@code organizer_id}), som scoper INDEN I
 * én operatør og håndhæves i applikationen.
 */
public record OperatorId(UUID value) {

	public OperatorId {
		Objects.requireNonNull(value, "operator id must not be null");
	}

	@Override
	public String toString() {
		return value.toString();
	}

}
