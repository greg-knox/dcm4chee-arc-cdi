/* ***** BEGIN LICENSE BLOCK *****
 * Version: MPL 1.1/GPL 2.0/LGPL 2.1
 *
 * The contents of this file are subject to the Mozilla Public License Version
 * 1.1 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * http://www.mozilla.org/MPL/
 *
 * Software distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 * for the specific language governing rights and limitations under the
 * License.
 *
 * The Original Code is part of dcm4che, an implementation of DICOM(TM) in
 * Java(TM), hosted at https://github.com/gunterze/dcm4che.
 *
 * The Initial Developer of the Original Code is
 * Agfa Healthcare.
 * Portions created by the Initial Developer are Copyright (C) 2011
 * the Initial Developer. All Rights Reserved.
 *
 * Contributor(s):
 * See @authors listed below
 *
 * Alternatively, the contents of this file may be used under the terms of
 * either the GNU General Public License Version 2 or later (the "GPL"), or
 * the GNU Lesser General Public License Version 2.1 or later (the "LGPL"),
 * in which case the provisions of the GPL or the LGPL are applicable instead
 * of those above. If you wish to allow use of your version of this file only
 * under the terms of either the GPL or the LGPL, and not to allow others to
 * use your version of this file under the terms of the MPL, indicate your
 * decision by deleting the provisions above and replace them with the notice
 * and other provisions required by the GPL or the LGPL. If you do not delete
 * the provisions above, a recipient may use your version of this file under
 * the terms of any one of the MPL, the GPL or the LGPL.
 *
 * ***** END LICENSE BLOCK ***** */

package org.dcm4chee.archive.compress.impl;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.security.MessageDigest;

import javax.inject.Inject;

import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.BulkData;
import org.dcm4che3.data.Tag;
import org.dcm4che3.imageio.codec.CompressionRule;
import org.dcm4che3.imageio.codec.CompressionRules;
import org.dcm4che3.net.Status;
import org.dcm4che3.net.service.DicomServiceException;
import org.dcm4che3.util.TagUtils;
import org.dcm4chee.archive.compress.CompressionService;
import org.dcm4chee.archive.conf.ArchiveAEExtension;
import org.dcm4chee.archive.store.DelegatingStoreService;
import org.dcm4chee.archive.store.StoreContext;
import org.dcm4chee.archive.store.StoreSession;
import org.dcm4chee.conf.cdi.dynamicdecorators.DynamicDecorator;
import org.dcm4chee.storage.ObjectAlreadyExistsException;
import org.dcm4chee.storage.StorageContext;
import org.dcm4chee.storage.service.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Umberto Cappellini <umberto.cappellini@agfa.com>
 * 
 */
@DynamicDecorator
public class StoreServiceCompressDecorator extends DelegatingStoreService {

    static Logger LOG = LoggerFactory.getLogger(StoreServiceCompressDecorator.class);

    @Inject
    private StorageService storageService;

    @Inject
    private CompressionService compressionService;

    @Override
    public void processFile(StoreContext context)
            throws DicomServiceException {
        
        // if possible, compress the file, store on file system and
        // update store context. Otherwise call the standard moveFile.
        if (!compress(context)) {
            getNextDecorator().processFile(context);
        }
    }

    private boolean compress(StoreContext context) throws DicomServiceException {
        Attributes attrs = context.getAttributes();
        Object pixelData = attrs.getValue(Tag.PixelData);
        if (!(pixelData instanceof BulkData))
            return false;
        
        StoreSession session = context.getStoreSession();
        ArchiveAEExtension arcAE = session.getArchiveAEExtension();
        CompressionRules rules = arcAE.getCompressionRules();
        CompressionRule rule = rules.findCompressionRule(session.getRemoteAET(), attrs);
        if (rule == null)
            return false;
        else
            LOG.info("Compression rule selected:"+rule.getCommonName());

        MessageDigest digest = session.getMessageDigest();
        StorageContext storageContext =
                storageService.createStorageContext(session.getStorageSystem());
        Path source = context.getSpoolFile();
        String origStoragePath = context.calcStoragePath();
        String storagePath = origStoragePath;
        int copies = 1;
        OutputStream out;
        for (;;)
            try {
                out = storageService.openOutputStream(storageContext, storagePath);
                break;
            } catch (ObjectAlreadyExistsException e) {
                storagePath = origStoragePath + '.' + copies++;
            } catch (Exception e) {
                throw new DicomServiceException(Status.UnableToProcess, e);
            }
        try {
            try {
                compressionService.compress(rule, source, out, digest,
                                context.getTransferSyntax(), attrs);
            } finally {
                out.close();
            }
        } catch (Exception e) {
            LOG.info("{}: Compression failed:", session, e);
            try {
                storageService.deleteObject(storageContext, storagePath);
            } catch (IOException e1) {
                LOG.warn("{}: Failed to delete compressed file - {}",
                        context.getStoreSession(), storagePath, e);
            }
            return false;
        }
        context.setStorageContext(storageContext);
        context.setStoragePath(storagePath);
        context.setFinalFileSize(storageContext.getFileSize());
        context.setTransferSyntax(rule.getTransferSyntax());
        if (digest != null) {
            context.setFinalFileDigest(
                    TagUtils.toHexString(digest.digest()));
        }
        return true;
     }

}
