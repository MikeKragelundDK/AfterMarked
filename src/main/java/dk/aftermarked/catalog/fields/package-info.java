/**
 * Feltværdi-lageret: custom felter deklareret pr. operatør, gemt som JSONB på
 * entiteten (PLAN §2.6). Egen navngiven grænseflade, så et modul kan få
 * kataloget UDEN at få felterne.
 *
 * <p>Bemærk formen på en tilladelse: {@code "catalog::*"} dækker alle NAVNGIVNE
 * grænseflader, men IKKE basispakken. Et modul der skal bruge både den pligtige
 * kerne og felterne (fx {@code web} senere) skal liste BEGGE:
 * {@code {"catalog", "catalog::fields"}}.
 *
 * <p>Typerne her holder sig med vilje til {@code java.*}. De må ikke trække
 * hverken Jackson eller katalogets aggregater med sig: grænsefladen er dén,
 * {@code web} og {@code operatoradmin} kommer til at afhænge af, og
 * {@code @NamedInterface} propagerer sit medlemskab til typer, der optræder i
 * offentlige signaturer i samme pakkerum. En reference herfra til
 * {@code dk.aftermarked.catalog} ville dermed kunne flytte en kernetype ind i
 * grænsefladen — stille, og i den forkerte retning.
 */
@NamedInterface("fields")
package dk.aftermarked.catalog.fields;

import org.springframework.modulith.NamedInterface;
