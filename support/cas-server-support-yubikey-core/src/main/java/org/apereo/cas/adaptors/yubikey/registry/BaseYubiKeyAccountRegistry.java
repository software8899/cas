package org.apereo.cas.adaptors.yubikey.registry;

import org.apereo.cas.adaptors.yubikey.YubiKeyAccount;
import org.apereo.cas.adaptors.yubikey.YubiKeyAccountRegistry;
import org.apereo.cas.adaptors.yubikey.YubiKeyAccountValidator;
import org.apereo.cas.adaptors.yubikey.YubiKeyDeviceRegistrationRequest;
import org.apereo.cas.adaptors.yubikey.YubiKeyRegisteredDevice;
import org.apereo.cas.util.LoggingUtils;
import org.apereo.cas.util.crypto.CipherExecutor;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import lombok.val;

import javax.persistence.NoResultException;
import java.io.Serializable;
import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * This is {@link BaseYubiKeyAccountRegistry}.
 *
 * @author Misagh Moayyed
 * @author Dmitriy Kopylenko
 * @since 5.2.0
 */
@Slf4j
@ToString
@RequiredArgsConstructor(access = AccessLevel.PROTECTED)
@Getter
@Setter
public abstract class BaseYubiKeyAccountRegistry implements YubiKeyAccountRegistry {

    private final YubiKeyAccountValidator accountValidator;

    private CipherExecutor<Serializable, String> cipherExecutor = CipherExecutor.noOpOfSerializableToString();

    @Override
    public boolean isYubiKeyRegisteredFor(final String uid, final String yubikeyPublicId) {
        try {
            val account = getAccount(uid);
            if (account.isPresent()) {
                val yubiKeyAccount = account.get();
                return yubiKeyAccount.getDevices()
                    .stream()
                    .anyMatch(device -> device.getPublicId().equals(yubikeyPublicId));
            }
        } catch (final NoSuchElementException | NoResultException e) {
            LOGGER.debug("No registration record could be found for id [{}] and public id [{}]", uid, yubikeyPublicId);
        } catch (final Exception e) {
            LOGGER.debug(e.getMessage(), e);
        }
        return false;
    }

    @Override
    public boolean isYubiKeyRegisteredFor(final String uid) {
        try {
            val account = getAccount(uid);
            return account.isPresent() && !account.get().getDevices().isEmpty();
        } catch (final NoResultException e) {
            LOGGER.debug("No registration record could be found for id [{}]", uid);
        } catch (final Exception e) {
            LoggingUtils.error(LOGGER, e);
        }
        return false;
    }

    @Override
    public final Collection<? extends YubiKeyAccount> getAccounts() {
        val currentDevices = getAccountsInternal();
        return currentDevices
            .stream()
            .peek(it -> {
                val devices = it.getDevices()
                    .stream()
                    .map(device -> {
                        try {
                            val pubId = getCipherExecutor().decode(device.getPublicId());
                            device.setPublicId(pubId);
                            return device;
                        } catch (final Exception e) {
                            LoggingUtils.error(LOGGER, e);
                            delete(it.getUsername(), device.getId());
                        }
                        return null;
                    })
                    .filter(Objects::nonNull)
                    .collect(Collectors.toCollection(ArrayList::new));
                it.setDevices(devices);
            })
            .collect(Collectors.toList());
    }

    @Override
    public boolean registerAccountFor(final YubiKeyDeviceRegistrationRequest request) {
        if (accountValidator.isValid(request.getUsername(), request.getToken())) {
            val yubikeyPublicId = getCipherExecutor().encode(accountValidator.getTokenPublicId(request.getToken()));

            val device = YubiKeyRegisteredDevice.builder()
                .id(System.currentTimeMillis())
                .name(request.getName())
                .publicId(yubikeyPublicId)
                .registrationDate(ZonedDateTime.now(Clock.systemUTC()))
                .build();

            var result = getAccount(request.getUsername());
            if (result.isEmpty()) {
                return saveAccount(request, device) != null;
            }
            val account = result.get();
            account.getDevices().add(device);
            return update(account);
        }
        return false;
    }

    /**
     * Save account.
     *
     * @param request the request
     * @param device  the device
     * @return the boolean
     */
    protected abstract YubiKeyAccount saveAccount(YubiKeyDeviceRegistrationRequest request, YubiKeyRegisteredDevice... device);

    /**
     * Update.
     *
     * @param account the account
     * @return boolean
     */
    protected abstract boolean update(YubiKeyAccount account);

    /**
     * Gets accounts internal.
     *
     * @return the accounts internal
     */
    protected abstract Collection<? extends YubiKeyAccount> getAccountsInternal();
}
