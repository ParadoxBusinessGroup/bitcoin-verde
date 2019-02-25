package com.softwareverde.bitcoin.server.module.stratum.api.endpoint.account;

import com.softwareverde.bitcoin.address.Address;
import com.softwareverde.bitcoin.address.AddressInflater;
import com.softwareverde.bitcoin.miner.pool.AccountId;
import com.softwareverde.bitcoin.server.Configuration;
import com.softwareverde.bitcoin.server.module.stratum.api.endpoint.StratumApiResult;
import com.softwareverde.bitcoin.server.module.stratum.database.AccountDatabaseManager;
import com.softwareverde.database.DatabaseException;
import com.softwareverde.database.mysql.MysqlDatabaseConnection;
import com.softwareverde.database.mysql.MysqlDatabaseConnectionFactory;
import com.softwareverde.io.Logger;
import com.softwareverde.servlet.AuthenticatedServlet;
import com.softwareverde.servlet.GetParameters;
import com.softwareverde.servlet.PostParameters;
import com.softwareverde.servlet.request.Request;
import com.softwareverde.servlet.response.JsonResponse;
import com.softwareverde.servlet.response.Response;

import static com.softwareverde.servlet.response.Response.ResponseCodes;

public class PasswordApi extends AuthenticatedServlet {
    protected final MysqlDatabaseConnectionFactory _databaseConnectionFactory;

    public PasswordApi(final Configuration.StratumProperties stratumProperties, final MysqlDatabaseConnectionFactory databaseConnectionFactory) {
        super(stratumProperties);
        _databaseConnectionFactory = databaseConnectionFactory;
    }

    @Override
    protected Response _onAuthenticatedRequest(final AccountId accountId, final Request request) {
        final GetParameters getParameters = request.getGetParameters();
        final PostParameters postParameters = request.getPostParameters();

        if (request.getMethod() != Request.HttpMethod.POST) {
            return new JsonResponse(ResponseCodes.BAD_REQUEST, new StratumApiResult(false, "Invalid method."));
        }

        {   // CREATE ACCOUNT
            // Requires GET:
            // Requires POST: password, new_password

            final String currentPassword = postParameters.get("password");
            final String newPassword = postParameters.get("newPassword");

            if (newPassword.length() < CreateAccountApi.MIN_PASSWORD_LENGTH) {
                return new JsonResponse(ResponseCodes.BAD_REQUEST, new StratumApiResult(false, "Invalid password length."));
            }

            try (final MysqlDatabaseConnection databaseConnection = _databaseConnectionFactory.newConnection()) {
                final AccountDatabaseManager accountDatabaseManager = new AccountDatabaseManager(databaseConnection);

                final Boolean passwordIsCorrect = accountDatabaseManager.authenticateAccount(accountId, currentPassword);
                if (! passwordIsCorrect) {
                    return new JsonResponse(ResponseCodes.OK, new StratumApiResult(false, "Invalid credentials."));
                }

                accountDatabaseManager.setAccountPassword(accountId, newPassword);

                return new JsonResponse(ResponseCodes.OK, new StratumApiResult(true, null));
            }
            catch (final DatabaseException exception) {
                Logger.log(exception);
                return new JsonResponse(ResponseCodes.SERVER_ERROR, new StratumApiResult(false, "An internal error occurred."));
            }
        }
    }
}