package dk.aftermarked.tenancy;

/** Operatøren som læst fra databasen — kun den række, RLS lader os se. */
public record Operator(OperatorId id, String slug, String name, String hostname) {
}
