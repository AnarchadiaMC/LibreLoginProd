/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package xyz.kyngs.librelogin.common.migrate;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import xyz.kyngs.librelogin.api.Logger;
import xyz.kyngs.librelogin.api.database.User;
import xyz.kyngs.librelogin.api.database.connector.DatabaseConnector;
import xyz.kyngs.librelogin.api.database.connector.SQLDatabaseConnector;
import xyz.kyngs.librelogin.api.premium.PremiumException;
import xyz.kyngs.librelogin.api.premium.PremiumProvider;
import xyz.kyngs.librelogin.api.premium.PremiumUser;
import xyz.kyngs.librelogin.common.util.GeneralUtil;

/**
 * Reads FastLogin migration data and applies premium UUID information to LibreLogin's database.
 */
public class FastLoginSQLMigrateReadProvider extends SQLMigrateReadProvider {
    private final DatabaseConnector<?, ?> destinationConnector;
    private final PremiumProvider premiumProvider;

    /**
     * Creates a migration reader for FastLogin's premium account table.
     *
     * @param tableName FastLogin table name
     * @param logger plugin logger
     * @param connector source SQL connector
     * @param main destination database connector
     * @param provider premium profile lookup service
     */
    public FastLoginSQLMigrateReadProvider(
            String tableName,
            Logger logger,
            SQLDatabaseConnector connector,
            DatabaseConnector<?, ?> main,
            PremiumProvider provider) {
        super(tableName, logger, connector);
        this.destinationConnector = main;
        this.premiumProvider = provider;
    }

    /**
     * Reads every premium FastLogin entry and writes the resolved premium UUID into the active
     * LibreLogin database.
     *
     * @return empty collection because this migration mutates premium metadata in place
     */
    @Override
    public Collection<User> getAllUsers() {
        return connector.runQuery(
                connection -> {
                    var statement =
                            connection.prepareStatement("SELECT * FROM `%s`".formatted(tableName));
                    var resultSet = statement.executeQuery();
                    Multimap<UUID, String> premiumNamesByUuid = HashMultimap.create();

                    while (resultSet.next()) {
                        try {
                            if (resultSet.getInt("Premium") != 1) {
                                continue;
                            }

                            UUID premiumUuid =
                                    GeneralUtil.fromUnDashedUUID(resultSet.getString("UUID"));
                            String lastKnownName = resultSet.getString("Name");
                            premiumNamesByUuid.put(premiumUuid, lastKnownName);
                        } catch (Exception exception) {
                            logger.error("Error while migrating user from FastLogin db, omitting");
                        }
                    }

                    for (Map.Entry<UUID, Collection<String>> entry :
                            premiumNamesByUuid.asMap().entrySet()) {
                        UUID premiumUuid = entry.getKey();
                        Collection<String> candidateNames = entry.getValue();
                        if (premiumUuid == null) {
                            continue;
                        }

                        String resolvedName = null;
                        if (candidateNames.size() == 1) {
                            resolvedName = candidateNames.iterator().next();
                        } else if (candidateNames.size() > 1) {
                            logger.warn(
                                    "Users %s share the same premium UUID %s, contacting mojang to find the owner"
                                            .formatted(
                                                    Arrays.toString(candidateNames.toArray()),
                                                    premiumUuid));
                            PremiumUser resolvedProfile = null;

                            while (true) {
                                try {
                                    resolvedProfile = premiumProvider.getUserForUUID(premiumUuid);
                                    break;
                                } catch (PremiumException exception) {
                                    if (exception.getIssue() == PremiumException.Issue.THROTTLED) {
                                        logger.warn(
                                                "Request to mojang throttled, waiting for 5"
                                                        + " seconds");
                                        try {
                                            Thread.sleep(5000);
                                        } catch (InterruptedException interrupted) {
                                            throw new RuntimeException(
                                                    interrupted);
                                        }
                                    } else {
                                        logger.error(
                                                "Cannot contact mojang to find the owner,"
                                                        + " omitting");
                                        exception.printStackTrace();
                                        break;
                                    }
                                }
                            }

                            if (resolvedProfile == null) {
                                logger.warn(
                                        "No owner found for the premium UUID %s, omitting"
                                                .formatted(premiumUuid));
                                continue;
                            }

                            for (String candidateName : candidateNames) {
                                if (candidateName.equalsIgnoreCase(resolvedProfile.name())) {
                                    resolvedName = candidateName;
                                    break;
                                }
                            }

                            if (resolvedName == null) {
                                logger.error(
                                        "Registered names with the premium UUID do not match the mojang name %s, omitting"
                                                .formatted(resolvedProfile.name()));
                                continue;
                            } else {
                                logger.info(
                                        "Found owner of the premium UUID %s, name %s"
                                                .formatted(premiumUuid, resolvedName));
                            }
                        } else {
                            continue;
                        }

                        assert resolvedName != null;

                        if (destinationConnector instanceof SQLDatabaseConnector sqlDestination) {
                            String persistedName = resolvedName;
                            sqlDestination.runQuery(
                                    destinationConnection -> {
                                        var updateStatement =
                                                destinationConnection.prepareStatement(
                                                        "UPDATE librepremium_data SET"
                                                                + " premium_uuid=? WHERE"
                                                                + " last_nickname=?");
                                        updateStatement.setString(1, premiumUuid.toString());
                                        updateStatement.setString(2, persistedName);
                                        updateStatement.executeUpdate();
                                    });
                        }
                    }

                    return List.of();
                });
    }
}
