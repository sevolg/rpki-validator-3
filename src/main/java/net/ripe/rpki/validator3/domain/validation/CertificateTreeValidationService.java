/**
 * The BSD License
 *
 * Copyright (c) 2010-2018 RIPE NCC
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *   - Redistributions of source code must retain the above copyright notice,
 *     this list of conditions and the following disclaimer.
 *   - Redistributions in binary form must reproduce the above copyright notice,
 *     this list of conditions and the following disclaimer in the documentation
 *     and/or other materials provided with the distribution.
 *   - Neither the name of the RIPE NCC nor the names of its contributors may be
 *     used to endorse or promote products derived from this software without
 *     specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
package net.ripe.rpki.validator3.domain.validation;

import com.google.common.base.Objects;
import lombok.extern.slf4j.Slf4j;
import net.ripe.rpki.commons.crypto.CertificateRepositoryObject;
import net.ripe.rpki.commons.crypto.cms.manifest.ManifestCms;
import net.ripe.rpki.commons.crypto.crl.X509Crl;
import net.ripe.rpki.commons.crypto.x509cert.X509ResourceCertificate;
import net.ripe.rpki.commons.util.RepositoryObjectType;
import net.ripe.rpki.commons.validation.ValidationLocation;
import net.ripe.rpki.commons.validation.ValidationOptions;
import net.ripe.rpki.commons.validation.ValidationResult;
import net.ripe.rpki.commons.validation.ValidationString;
import net.ripe.rpki.commons.validation.objectvalidators.CertificateRepositoryObjectValidationContext;
import net.ripe.rpki.validator3.domain.CertificateTreeValidationRun;
import net.ripe.rpki.validator3.domain.RpkiObject;
import net.ripe.rpki.validator3.domain.RpkiObjects;
import net.ripe.rpki.validator3.domain.RpkiRepositories;
import net.ripe.rpki.validator3.domain.RpkiRepository;
import net.ripe.rpki.validator3.domain.TrustAnchor;
import net.ripe.rpki.validator3.domain.TrustAnchors;
import net.ripe.rpki.validator3.domain.ValidationRuns;
import net.ripe.rpki.validator3.util.Sha256;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.persistence.EntityManager;
import javax.persistence.FlushModeType;
import javax.transaction.Transactional;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static java.util.stream.Collectors.toList;

@Service
@Slf4j
public class CertificateTreeValidationService {
    private static final ValidationOptions VALIDATION_OPTIONS = new ValidationOptions();
    private final EntityManager entityManager;
    private final TrustAnchors trustAnchors;
    private final RpkiObjects rpkiObjects;
    private final RpkiRepositories rpkiRepositories;
    private final ValidationRuns validationRuns;

    @Autowired
    public CertificateTreeValidationService(
        EntityManager entityManager,
        TrustAnchors trustAnchors,
        RpkiObjects rpkiObjects,
        RpkiRepositories rpkiRepositories,
        ValidationRuns validationRuns
    ) {
        this.entityManager = entityManager;
        this.trustAnchors = trustAnchors;
        this.rpkiObjects = rpkiObjects;
        this.rpkiRepositories = rpkiRepositories;
        this.validationRuns = validationRuns;
    }

    @Transactional(Transactional.TxType.REQUIRED)
    public void validate(long trustAnchorId) {
        Map<URI, RpkiRepository> registeredRepositories = new HashMap<>();
        entityManager.setFlushMode(FlushModeType.COMMIT);

        TrustAnchor trustAnchor = trustAnchors.get(trustAnchorId);
        log.info("starting tree validation for {}", trustAnchor);

        CertificateTreeValidationRun validationRun = new CertificateTreeValidationRun(trustAnchor);
        validationRuns.add(validationRun);

        String trustAnchorLocation = trustAnchor.getLocations().get(0);
        ValidationResult validationResult = ValidationResult.withLocation(trustAnchorLocation);

        try {
            X509ResourceCertificate certificate = trustAnchor.getCertificate();
            validationResult.rejectIfNull(certificate, "trust.anchor.certificate.available");
            if (certificate == null) {
                return;
            }

            CertificateRepositoryObjectValidationContext context = new CertificateRepositoryObjectValidationContext(
                URI.create(trustAnchorLocation),
                certificate
            );

            certificate.validate(trustAnchorLocation, context, null, null, VALIDATION_OPTIONS, validationResult);
            if (validationResult.hasFailureForCurrentLocation()) {
                return;
            }

            URI locationUri = Objects.firstNonNull(certificate.getRrdpNotifyUri(), certificate.getRepositoryUri());
            validationResult.warnIfNull(locationUri, "trust.anchor.certificate.rrdp.notify.uri.or.repository.uri.present");
            if (locationUri == null) {
                return;
            }

            validationRun.getValidatedObjects().addAll(
                validateCertificateAuthority(trustAnchor, registeredRepositories, context, validationResult)
            );
        } finally {
            validationRun.completeWith(validationResult);
            log.info("tree validation {} for {}", validationRun.getStatus(), trustAnchor);
        }
    }

    private List<RpkiObject> validateCertificateAuthority(TrustAnchor trustAnchor, Map<URI, RpkiRepository> registeredRepositories, CertificateRepositoryObjectValidationContext context, ValidationResult validationResult) {
        List<RpkiObject> validatedObjects = new ArrayList<>();

        ValidationLocation certificateLocation = validationResult.getCurrentLocation();
        ValidationResult temporary = ValidationResult.withLocation(certificateLocation);
        try {
            RpkiRepository rpkiRepository = registerRepository(trustAnchor, registeredRepositories, context);

            temporary.warnIfTrue(rpkiRepository.isPending(), "rpki.repository.pending", rpkiRepository.getLocationUri());
            temporary.rejectIfTrue(rpkiRepository.isFailed(), "rpki.repository.failed", rpkiRepository.getLocationUri());
            if (rpkiRepository.isPending() || temporary.hasFailureForCurrentLocation()) {
                return validatedObjects;
            }

            X509ResourceCertificate certificate = context.getCertificate();
            URI manifestUri = certificate.getManifestUri();
            temporary.setLocation(new ValidationLocation(manifestUri));

            Optional<RpkiObject> manifestObject = rpkiObjects.findLatestByTypeAndAuthorityKeyIdentifier(RpkiObject.Type.MFT, context.getSubjectKeyIdentifier());
            temporary.rejectIfFalse(manifestObject.isPresent(), ValidationString.VALIDATOR_CA_SHOULD_HAVE_MANIFEST, manifestUri.toASCIIString());
            Optional<ManifestCms> maybeManifest = manifestObject.flatMap(x -> x.get(ManifestCms.class, temporary));
            if (temporary.hasFailureForCurrentLocation()) {
                return validatedObjects;
            }

            ManifestCms manifest = maybeManifest.get();
            List<Map.Entry<String, byte[]>> crlEntries = manifest.getFiles().entrySet().stream()
                .filter((entry) -> RepositoryObjectType.parse(entry.getKey()) == RepositoryObjectType.Crl)
                .collect(toList());
            temporary.rejectIfFalse(crlEntries.size() == 1, "manifest.contains.one.crl.entry", String.valueOf(crlEntries.size()));
            if (temporary.hasFailureForCurrentLocation()) {
                return validatedObjects;
            }

            Map.Entry<String, byte[]> crlEntry = crlEntries.get(0);
            URI crlUri = manifestUri.resolve(crlEntry.getKey());

            Optional<RpkiObject> crlObject = rpkiObjects.findBySha256(crlEntry.getValue());
            temporary.rejectIfFalse(crlObject.isPresent(), "rpki.crl.found", crlUri.toASCIIString());
            if (temporary.hasFailureForCurrentLocation()) {
                return validatedObjects;
            }

            temporary.setLocation(new ValidationLocation(crlUri));
            Optional<X509Crl> crl = crlObject.flatMap(x -> x.get(X509Crl.class, temporary));
            if (temporary.hasFailureForCurrentLocation()) {
                return validatedObjects;
            }

            crl.get().validate(crlUri.toASCIIString(), context, null, VALIDATION_OPTIONS, temporary);
            if (temporary.hasFailureForCurrentLocation()) {
                return validatedObjects;
            }

            temporary.setLocation(new ValidationLocation(manifestUri));
            manifest.validate(manifestUri.toASCIIString(), context, crl.get(), manifest.getCrlUri(), VALIDATION_OPTIONS, temporary);
            if (temporary.hasFailureForCurrentLocation()) {
                return validatedObjects;
            }
            validatedObjects.add(manifestObject.get());

            Map<URI, RpkiObject> manifestEntries = retrieveManifestEntries(manifest, manifestUri, temporary);

            manifestEntries.forEach((location, obj) -> {
                temporary.setLocation(new ValidationLocation(location));
                temporary.rejectIfFalse(Arrays.equals(Sha256.hash(obj.getEncoded()), obj.getSha256()), "rpki.object.sha256.matches");
                if (temporary.hasFailureForCurrentLocation()) {
                    return;
                }

                Optional<CertificateRepositoryObject> maybeCertificateRepositoryObject = obj.get(CertificateRepositoryObject.class, temporary);
                if (temporary.hasFailureForCurrentLocation()) {
                    return;
                }

                maybeCertificateRepositoryObject.ifPresent(certificateRepositoryObject -> {
                    certificateRepositoryObject.validate(location.toASCIIString(), context, crl.get(), crlUri, VALIDATION_OPTIONS, temporary);

                    if (!temporary.hasFailureForCurrentLocation()) {
                        validatedObjects.add(obj);
                    }

                    if (certificateRepositoryObject instanceof X509ResourceCertificate
                        && ((X509ResourceCertificate) certificateRepositoryObject).isCa()
                        && !temporary.hasFailureForCurrentLocation()) {

                        CertificateRepositoryObjectValidationContext childContext = context.createChildContext(location, (X509ResourceCertificate) certificateRepositoryObject);
                        validatedObjects.addAll(validateCertificateAuthority(trustAnchor, registeredRepositories, childContext, temporary));
                    }
                });
            });
        } catch (Exception e) {
            validationResult.error("exception.occurred", e.toString(), ExceptionUtils.getStackTrace(e));
        } finally {
            validationResult.addAll(temporary);
        }

        return validatedObjects;
    }

    private RpkiRepository registerRepository(TrustAnchor trustAnchor, Map<URI, RpkiRepository> registeredRepositories, CertificateRepositoryObjectValidationContext context) {
        if (context.getRpkiNotifyURI() != null) {
            return registeredRepositories.computeIfAbsent(context.getRpkiNotifyURI(), (uri) -> rpkiRepositories.register(
                trustAnchor,
                uri.toASCIIString(),
                RpkiRepository.Type.RRDP
            ));
        } else {
            return registeredRepositories.computeIfAbsent(context.getRepositoryURI(), (uri) -> rpkiRepositories.register(
                trustAnchor,
                uri.toASCIIString(),
                RpkiRepository.Type.RSYNC
            ));
        }
    }

    private Map<URI, RpkiObject> retrieveManifestEntries(ManifestCms manifest, URI manifestUri, ValidationResult
        validationResult) {
        Map<URI, RpkiObject> result = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> entry : manifest.getFiles().entrySet()) {
            URI location = manifestUri.resolve(entry.getKey());
            validationResult.setLocation(new ValidationLocation(location));

            Optional<RpkiObject> object = rpkiObjects.findBySha256(entry.getValue());
            validationResult.rejectIfFalse(object.isPresent(), "manifest.entry.found", manifestUri.toASCIIString());

            object.ifPresent(obj -> {
                boolean hashMatches = Arrays.equals(obj.getSha256(), entry.getValue());
                validationResult.rejectIfFalse(hashMatches, "manifest.entry.hash.matches", entry.getKey());
                if (!hashMatches) {
                    return;
                }

                result.put(location, obj);
            });
        }
        return result;
    }
}
