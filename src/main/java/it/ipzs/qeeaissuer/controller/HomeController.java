package it.ipzs.qeeaissuer.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.ModelAndView;

import it.ipzs.qeeaissuer.oidclib.OidcConfig;
import it.ipzs.qeeaissuer.oidclib.OidcWrapper;
import it.ipzs.qeeaissuer.oidclib.schemas.WellKnownData;
import jakarta.servlet.http.HttpServletRequest;


@RestController
@RequestMapping("/admin/federation")
public class HomeController {

	@Autowired
	private OidcWrapper oidcWrapper;
	
	@Autowired
	private OidcConfig oidcConfig;
	
	@GetMapping(path = { "/","/home" })
	public ModelAndView home(HttpServletRequest request)
		throws Exception {

		ModelAndView mav = new ModelAndView("home");

		WellKnownData wellKnow = oidcWrapper.getFederationEntityData();

		mav.addObject("onlyJwks", wellKnow.hasOnlyJwks());
		mav.addObject("intermediate", wellKnow.isIntermediate());
		mav.addObject("showLanding", wellKnow.isComplete());
		mav.addObject("trustAnchorHost", oidcConfig.getHosts().getTrustAnchor());

		if (wellKnow.hasOnlyJwks()) {
			mav.addObject("mineJwks", wellKnow.getValue());
			mav.addObject("configFile", oidcConfig.getRelyingParty().getJwkFilePath());
		}

		if (wellKnow.isIntermediate()) {
			mav.addObject("rpName", oidcConfig.getRelyingParty().getApplicationName());
			mav.addObject("rpClientId", oidcConfig.getRelyingParty().getClientId());
			mav.addObject("rpPublicJwks", wellKnow.getPublicJwks());
			mav.addObject(
				"configFile", oidcConfig.getRelyingParty().getTrustMarksFilePath());
		}

		return mav;
	}

}