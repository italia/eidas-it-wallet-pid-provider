package it.ipzs.qeeaissuer.service;

import java.text.ParseException;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;

import com.authlete.sd.Disclosure;
import com.authlete.sd.SDJWT;
import com.authlete.sd.SDObjectBuilder;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jwt.JWTClaimsSet;

import it.ipzs.qeeaissuer.dto.CredentialResponse;
import it.ipzs.qeeaissuer.dto.EvidenceDto;
import it.ipzs.qeeaissuer.dto.PlaceOfBirthDto;
import it.ipzs.qeeaissuer.dto.ProofRequest;
import it.ipzs.qeeaissuer.dto.RecordDto;
import it.ipzs.qeeaissuer.dto.SourceDto;
import it.ipzs.qeeaissuer.dto.VerifiedClaims;
import it.ipzs.qeeaissuer.model.SessionInfo;
import it.ipzs.qeeaissuer.util.AccessTokenUtil;
import it.ipzs.qeeaissuer.util.CredentialProofUtil;
import it.ipzs.qeeaissuer.util.DpopUtil;
import it.ipzs.qeeaissuer.util.SdJwtUtil;
import it.ipzs.qeeaissuer.util.SessionUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class CredentialService {

	private final SRService srService;
	private final DpopUtil dpopUtil;
	private final AccessTokenUtil accessTokenUtil;
	private final CredentialProofUtil proofUtil;
	private final SdJwtUtil sdJwtUtil;
	private final SessionUtil sessionUtil;



	public CredentialResponse generateSdCredentialResponse(String dpop, ProofRequest proof)
			throws JOSEException, ParseException {
		String nonce = srService.generateRandomByByteLength(32);
		return CredentialResponse.builder().format("vc+sd-jwt")
				.nonce(nonce).nonceExpiresIn(86400).credential(generateSdJwtCredential(dpop, proof)).build();

	}

	private String generateSdJwtCredential(String dpop, ProofRequest proof)
			throws JOSEException, ParseException {

		SessionInfo sessionInfo = sessionUtil.getSessionInfo(proofUtil.getIssuer(proof.getJwt()));
		if (sessionInfo == null) {
			throw new RuntimeException("No client id known found");
		}

		JWK jwk = dpopUtil.getJwk(dpop);

		// FIXME test data
		Disclosure nameClaim = sdJwtUtil.generateGenericDisclosure("given_name", "Mario");
		Disclosure familyClaim = sdJwtUtil.generateGenericDisclosure("family_name", "Rossi");
		Disclosure birthdateClaim = sdJwtUtil.generateGenericDisclosure("birthdate", "1980-10-01");
		Disclosure placeOfBirthClaim = sdJwtUtil.generateGenericDisclosure("place_of_birth",
				PlaceOfBirthDto.builder().country("IT").locality("Rome").build());
		Disclosure taxClaim = sdJwtUtil.generateGenericDisclosure("tax_id_number", "TINIT-RSSMRA80A10H501A");


		VerifiedClaims vc = new VerifiedClaims();

		EvidenceDto ev = new EvidenceDto();
		ev.setType("electronic_record");

		RecordDto rec = new RecordDto();
		rec.setType("https://eudi.wallet.pdnd.gov.it");
		SourceDto src = new SourceDto();
		src.setCountry_code("IT");
		src.setOrganization_id("urn:eudi:it:organization_id:ipa_code:QLHCFC");
		src.setOrganization_name("Ragioneria Generale dello Stato");

		rec.setSource(src);

		ev.setRecord(rec);

		SDObjectBuilder builder = new SDObjectBuilder();
		builder.putSDClaim(nameClaim);
		builder.putSDClaim(familyClaim);
		builder.putSDClaim(birthdateClaim);
		builder.putSDClaim(placeOfBirthClaim);
		builder.putSDClaim(taxClaim);


		SDObjectBuilder evBuilder = new SDObjectBuilder();
		evBuilder.putClaim("assurance_level", "high");
		evBuilder.putClaim("trust_framework", "eidas");
		Disclosure evDisclosure = sdJwtUtil.generateGenericDisclosure("evidence", List.of(ev));
		evBuilder.putSDClaim(evDisclosure);

		vc.setClaims(builder.build());
		vc.setVerification(evBuilder.build());

		SDJWT sdjwt = new SDJWT(
				sdJwtUtil.generateCredential(vc, jwk),
				List.of(evDisclosure, nameClaim, familyClaim, birthdateClaim, placeOfBirthClaim,
						taxClaim));

		String sdJwtString = sdjwt.toString();
		// remove last tilde for SD-JWT draft 4 compliance
		return sdJwtString.substring(0, sdJwtString.lastIndexOf("~"));
	}

	public void checkDpop(String dpop) {
		try {
			JWTClaimsSet claimsSet = dpopUtil.parse(dpop);
			String htuClaim = dpopUtil.getHtuClaim(claimsSet);
			String htmClaim = dpopUtil.getHtmClaim(claimsSet);

			// TODO better check uri
			if (!htuClaim.endsWith("/credential") || !htmClaim.equals("POST")) {
				throw new IllegalArgumentException("Invalid claims: " + htmClaim + " - " + htuClaim);
			}
		} catch (ParseException | JOSEException e) {
			log.error("", e);
			throw new RuntimeException("", e);
		}
	}

	public void checkAuthorizationAndProof(String authorization, ProofRequest proof)
			throws ParseException, JOSEException {

		JWTClaimsSet tokenClaims = accessTokenUtil.parse(authorization);
		JWTClaimsSet proofClaims = proofUtil.parse(proof.getJwt());
		
		Object proofNonce = proofClaims.getClaim("nonce");
		Object proofClientId = proofClaims.getClaim("iss");
		Object tokenNonce = tokenClaims.getClaim("nonce");
		if(Stream.of(proofNonce, proofClientId, tokenNonce).anyMatch(Objects::isNull)) {
			log.error("proof Nonce {} - token nonce {} - proof clientId {} ", proofNonce, tokenNonce, proofClientId);
			throw new IllegalArgumentException("proof or authorization parameter missing required key");
		} else {
			boolean checkPassed = false;
			SessionInfo sessionInfo = sessionUtil.getSessionInfo((String) proofClientId);
			if (sessionInfo != null) {
				log.debug("session info {}", sessionInfo);
				String nonce = sessionInfo.getNonce();
				if (nonce.equals(tokenNonce) && nonce.equals(proofNonce)) {
					checkPassed = true;
				} else {
					log.debug("token nonce {} - proof nonce {} - nonce in session {}", tokenNonce, proofNonce, nonce);
				}
			}

			if (!checkPassed)
				throw new IllegalArgumentException("Nonce does not match");
		}

	}

}
