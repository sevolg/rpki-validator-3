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
package net.ripe.rpki.validator3.adapter.jpa;

import com.querydsl.core.types.dsl.PathBuilder;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.JPQLQuery;
import com.querydsl.jpa.impl.JPAQuery;
import net.ripe.rpki.validator3.domain.RpkiRepository;
import net.ripe.rpki.validator3.domain.TrustAnchor;
import net.ripe.rpki.validator3.domain.TrustAnchorValidationRun;
import net.ripe.rpki.validator3.domain.ValidationRun;
import net.ripe.rpki.validator3.domain.ValidationRuns;
import net.ripe.rpki.validator3.domain.querydsl.QCertificateTreeValidationRun;
import net.ripe.rpki.validator3.domain.querydsl.QRrdpRepositoryValidationRun;
import net.ripe.rpki.validator3.domain.querydsl.QTrustAnchorValidationRun;
import net.ripe.rpki.validator3.domain.querydsl.QValidationRun;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import javax.transaction.Transactional;
import java.util.List;
import java.util.Optional;

import static net.ripe.rpki.validator3.domain.querydsl.QCertificateTreeValidationRun.certificateTreeValidationRun;
import static net.ripe.rpki.validator3.domain.querydsl.QRrdpRepositoryValidationRun.rrdpRepositoryValidationRun;
import static net.ripe.rpki.validator3.domain.querydsl.QTrustAnchorValidationRun.trustAnchorValidationRun;
import static net.ripe.rpki.validator3.domain.querydsl.QValidationRun.validationRun;

@Repository
@Transactional(Transactional.TxType.REQUIRED)
public class JPAValidationRuns extends JPARepository<ValidationRun> implements ValidationRuns {
    private final QuartzValidationScheduler validationScheduler;

    @Autowired
    public JPAValidationRuns(QuartzValidationScheduler validationScheduler) {
        super(validationRun);
        this.validationScheduler = validationScheduler;
    }

    protected static <T extends ValidationRun> JPQLQuery<Long> latestSuccessfulValidationRuns() {
        QValidationRun latest = new QValidationRun("latest");
        return JPAExpressions
            .select(latest.id.max())
            .where(latest.status.eq(ValidationRun.Status.SUCCEEDED))
            .groupBy(
                JPAExpressions.type(latest),
                latest.as(QTrustAnchorValidationRun.class).trustAnchor,
                latest.as(QCertificateTreeValidationRun.class).trustAnchor,
                latest.as(QRrdpRepositoryValidationRun.class).rpkiRepository
            )
            .from(latest);
    }

    @Override
    public void removeAllForTrustAnchor(TrustAnchor trustAnchor) {
        queryFactory.delete(trustAnchorValidationRun).where(trustAnchorValidationRun.trustAnchor.eq(trustAnchor)).execute();
        queryFactory.delete(certificateTreeValidationRun).where(certificateTreeValidationRun.trustAnchor.eq(trustAnchor)).execute();
    }

    @Override
    public <T extends ValidationRun> List<T> findAll(Class<T> type) {
        return select(type)
            .orderBy(validationRun.updatedAt.desc(), validationRun.id.desc())
            .fetch();
    }

    @Override
    public <T extends ValidationRun> List<T> findLatestSuccessful(Class<T> type) {
        return select(type)
            .where(validationRun.id.in(latestSuccessfulValidationRuns()))
            .orderBy(validationRun.updatedAt.desc(), validationRun.id.desc())
            .fetch();
    }

    @Override
    public Optional<TrustAnchorValidationRun> findLatestCompletedForTrustAnchor(TrustAnchor trustAnchor) {
        return Optional.ofNullable(
            select(TrustAnchorValidationRun.class)
                .where(
                    validationRun.as(QTrustAnchorValidationRun.class).trustAnchor.eq(trustAnchor)
                        .and(validationRun.status.in(ValidationRun.Status.FAILED, ValidationRun.Status.SUCCEEDED))
                )
                .orderBy(validationRun.updatedAt.desc(), validationRun.id.desc())
                .fetchFirst()
        );
    }

    @Override
    public void runCertificateTreeValidation(TrustAnchor trustAnchor) {
        validationScheduler.triggerCertificateTreeValidation(trustAnchor);
    }

    @Override
    public void removeAllForRpkiRepository(RpkiRepository repository) {
        if (repository.getType() == RpkiRepository.Type.RRDP) {
            queryFactory.delete(rrdpRepositoryValidationRun).where(rrdpRepositoryValidationRun.rpkiRepository.eq(repository)).execute();
        } else {
            // RPKI repository deletion for rsync validation runs cascades on delete in the DB, nothing to do here
        }
    }


    protected <T extends ValidationRun> JPAQuery<T> select(Class<T> type) {
        return queryFactory.selectFrom(new PathBuilder<>(type, "validationRun"));
    }
}
