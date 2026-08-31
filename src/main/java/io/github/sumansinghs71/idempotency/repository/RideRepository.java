package io.github.sumansinghs71.idempotency.repository;

import io.github.sumansinghs71.idempotency.model.Ride;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface RideRepository extends JpaRepository<Ride, Long> {

    Optional<Ride> findByIdempotencyKeyId(long idempotencyKeyId);

    @Modifying
    @Query("UPDATE Ride r SET r.pspChargeId = :chargeId, r.status = :status "
            + "WHERE r.idempotencyKeyId = :keyId")
    int updateChargeResult(
            @Param("keyId") long keyId,
            @Param("chargeId") String chargeId,
            @Param("status") String status);
}
