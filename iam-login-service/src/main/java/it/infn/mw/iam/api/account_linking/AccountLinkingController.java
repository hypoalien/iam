package it.infn.mw.iam.api.account_linking;


import static java.lang.String.format;

import java.io.IOException;
import java.security.Principal;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import it.infn.mw.iam.authn.AbstractExternalAuthenticationToken;
import it.infn.mw.iam.authn.ExternalAuthenticationHandlerSupport;
import it.infn.mw.iam.authn.ExternalAuthenticationRegistrationInfo.ExternalAuthenticationType;
import it.infn.mw.iam.authn.x509.IamX509AuthenticationCredential;

@Controller
@RequestMapping(AccountLinkingController.ACCCOUNT_LINKING_BASE_RESOURCE)
public class AccountLinkingController extends ExternalAuthenticationHandlerSupport {
  final AccountLinkingService linkingService;

  @Autowired
  public AccountLinkingController(AccountLinkingService s) {
    linkingService = s;
  }


  @PreAuthorize("hasRole('USER')")
  @RequestMapping(value = "/X509", method = RequestMethod.DELETE)
  @ResponseStatus(value = HttpStatus.NO_CONTENT)
  public void unlinkX509Certificate(Principal principal, @RequestParam String certificateSubject) {

    linkingService.unlinkX509Certificate(principal, certificateSubject);
  }


  @PreAuthorize("hasRole('USER')")
  @RequestMapping(value = "/X509", method = RequestMethod.POST)
  public String linkX509Certificate(HttpSession session, Principal principal,
      RedirectAttributes attributes) {

    clearAccountLinkingSessionAttributes(session);
    
    try {
      IamX509AuthenticationCredential cred = getSavedX509AuthenticationCredential(session)
          .orElseThrow(() -> new IllegalArgumentException(
              format("No X.509 credential found in session for user '%s'", principal.getName())));
      
      linkingService.linkX509Certificate(principal, cred);
      saveX509LinkingSuccess(cred, attributes);

    } catch (Exception ex) {
      saveAccountLinkingError(ex, attributes);
    }

    return "redirect:/dashboard";
  }


  @PreAuthorize("hasRole('USER')")
  @RequestMapping(value = "/{type}", method = RequestMethod.POST)
  public void linkAccount(@PathVariable ExternalAuthenticationType type,
      @RequestParam(value = "id", required = false) String externalIdpId, Authentication authn,
      HttpServletRequest request, HttpServletResponse response) throws IOException {

    HttpSession session = request.getSession();

    clearAccountLinkingSessionAttributes(session);
    setupAccountLinkingSessionKey(session, type);
    saveAuthenticationInSession(session, authn);

    response.sendRedirect(mapExternalAuthenticationTypeToExternalAuthnURL(type, externalIdpId));
  }

  @PreAuthorize("hasRole('USER')")
  @RequestMapping(value = "/{type}/done", method = {RequestMethod.GET, RequestMethod.POST})
  public String finalizeAccountLinking(@PathVariable ExternalAuthenticationType type,
      Principal principal, final RedirectAttributes redirectAttributes, HttpServletRequest request,
      HttpServletResponse response) throws IOException {

    HttpSession session = request.getSession();

    if (!hasAccountLinkingDoneKey(session)) {
      throw new IllegalArgumentException("No account linking done key found in request.");
    }

    AbstractExternalAuthenticationToken<?> externalAuthenticationToken =
        getExternalAuthenticationTokenFromSession(session).orElseThrow(() -> {
          clearAccountLinkingSessionAttributes(session);

          return new IllegalArgumentException("No external authentication token found in session");
        });

    try {

      linkingService.linkExternalAccount(principal, externalAuthenticationToken);
      saveAccountLinkingSuccess(externalAuthenticationToken, redirectAttributes);

    } catch (Exception ex) {

      saveAccountLinkingError(ex, redirectAttributes);

    } finally {
      clearAccountLinkingSessionAttributes(session);
    }

    return "redirect:/dashboard";

  }

  @PreAuthorize("hasRole('USER')")
  @RequestMapping(value = "/{type}", method = RequestMethod.DELETE)
  @ResponseStatus(value = HttpStatus.NO_CONTENT)
  public void unlinkAccount(@PathVariable ExternalAuthenticationType type, Principal principal,
      @RequestParam("iss") String issuer, @RequestParam("sub") String subject,
      @RequestParam(name = "attr", required = false) String attributeId) {

    linkingService.unlinkExternalAccount(principal, type, issuer, subject, attributeId);
  }

  @ResponseStatus(value = HttpStatus.BAD_REQUEST)
  @ExceptionHandler(IllegalArgumentException.class)
  public String handleIllegalArgumentException(HttpServletRequest request, Exception ex) {
    return "iam/dashboard";
  }
}
