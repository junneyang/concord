package com.walmartlabs.concord.server;

/*-
 * *****
 * Concord
 * -----
 * Copyright (C) 2017 - 2018 Walmart Inc.
 * -----
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * =====
 */

import com.walmartlabs.concord.dependencymanager.DependencyManager;
import com.walmartlabs.concord.imports.ImportManager;
import com.walmartlabs.concord.imports.ImportManagerFactory;
import com.walmartlabs.concord.imports.RepositoryExporter;
import com.walmartlabs.concord.repository.Repository;
import com.walmartlabs.concord.repository.Snapshot;
import com.walmartlabs.concord.sdk.Secret;
import com.walmartlabs.concord.server.cfg.ImportConfiguration;
import com.walmartlabs.concord.server.org.OrganizationDao;
import com.walmartlabs.concord.server.org.secret.SecretManager;
import com.walmartlabs.concord.server.repository.RepositoryManager;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;

import static com.walmartlabs.concord.project.model.Import.SecretDefinition;
import static com.walmartlabs.concord.server.queueclient.message.ImportEntry.GitEntry;

@Named
public class ImportManagerProvider implements Provider<ImportManager> {

    private final ImportManagerFactory factory;

    @Inject
    public ImportManagerProvider(ImportConfiguration cfg, OrganizationDao organizationDao, SecretManager secretManager, RepositoryManager repositoryManager) throws IOException {
        this.factory = new ImportManagerFactory(new DependencyManager(cfg.getCacheDir()),
                new RepositoryExporterImpl(organizationDao, secretManager, repositoryManager));
    }

    @Override
    public ImportManager get() {
        return factory.create();
    }

    private class RepositoryExporterImpl implements RepositoryExporter {

        private final OrganizationDao organizationDao;
        private final SecretManager secretManager;
        private final RepositoryManager repositoryManager;

        private RepositoryExporterImpl(OrganizationDao organizationDao, SecretManager secretManager, RepositoryManager repositoryManager) {
            this.organizationDao = organizationDao;
            this.secretManager = secretManager;
            this.repositoryManager = repositoryManager;
        }

        @Override
        public Snapshot export(GitEntry entry, Path workDir) {
            Secret secret = getSecret(entry.secret());
            return repositoryManager.withLock(entry.url(), () -> {
                Repository repository = repositoryManager.fetch(entry.url(), entry.version(), null, entry.path(), secret);
                Path dst = workDir;
                if (entry.dest() != null) {
                    dst = dst.resolve(entry.dest());
                }
                return repository.export(dst);
            });
        }

        private Secret getSecret(SecretDefinition secret) {
            if (secret == null) {
                return null;
            }

            UUID orgId = organizationDao.getId(secret.org());
            if (orgId == null) {
                throw new RuntimeException("Error fetching secret '" + secret.name() + "': organization '" + secret.org() + "' not found");
            }

            SecretManager.DecryptedSecret s = secretManager.getSecret(null, orgId, secret.name(), secret.password(), null);
            if (s == null) {
                throw new RuntimeException("Secret not found: " + secret.name());
            }

            return s.getSecret();
        }
    }
}