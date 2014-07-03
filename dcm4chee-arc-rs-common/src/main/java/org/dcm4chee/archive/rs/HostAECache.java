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

package org.dcm4chee.archive.rs;


/**
 * @author Hesham Elbadawi <bsdreko@gmail.com>
 */

import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import org.dcm4che3.conf.api.ConfigurationException;
import org.dcm4che3.conf.api.ConfigurationNotFoundException;
import org.dcm4che3.conf.api.IApplicationEntityCache;
import org.dcm4che3.net.ApplicationEntity;
import org.dcm4che3.net.Device;
import org.dcm4chee.archive.conf.ArchiveDeviceExtension;
import org.dcm4chee.archive.conf.HostNameAEEntry;
import org.dcm4chee.archive.dto.Participant;

@ApplicationScoped
public class HostAECache {

    private static final String IPADDRESS_PATTERN = "^([01]?\\d\\d?|2[0-4]\\d|25[0-5])\\."
            + "([01]?\\d\\d?|2[0-4]\\d|25[0-5])\\."
            + "([01]?\\d\\d?|2[0-4]\\d|25[0-5])\\."
            + "([01]?\\d\\d?|2[0-4]\\d|25[0-5])$";

    @Inject
    IApplicationEntityCache aeCache;

    @Inject
    Device device;

    private ArchiveDeviceExtension arcDevExt;

    public boolean resolve() {
        return arcDevExt.isHostnameAEresoultion();
    }

    public void addHostAEEntry(HostNameAEEntry entry) {
        ArrayList<HostNameAEEntry> currentList = arcDevExt.getHostNameAEList();
        currentList.add(entry);
        arcDevExt.setHostNameAEList(currentList);
    }

    public ApplicationEntity findAE(HttpSource source)
            throws ConfigurationNotFoundException {
        arcDevExt = device
                .getDeviceExtension(ArchiveDeviceExtension.class);
        ApplicationEntity ae = getAE(source);
        if (ae != null)
            return ae;
        else
            throw new ConfigurationNotFoundException(
                    "FallBacK AE is not configured");
    }

    public ApplicationEntity getAE(HttpSource source) {

        // check if resoultion is enabled
        if (resolve()) {
            // check if it can get a host
            if (source.getHost() != Participant.UNKNOWN) {
                return lookupAE(source.getHost());
            } else {
                return getFallBack();
            }
        } else {
            // no resolution expect ip
            if (source.getIP() != Participant.UNKNOWN) {
                return lookupAE(source.getIP());
            } else {
                return getFallBack();
            }
        }
    }

    public boolean isIP(String str) {
        Pattern pattern = Pattern.compile(IPADDRESS_PATTERN);
        Matcher matcher = pattern.matcher(str);
        return matcher.matches();
    }

    public ApplicationEntity lookupAE(String host) {
        for (HostNameAEEntry entry : arcDevExt.getHostNameAEList()) {
            if (entry.getHostName().compareToIgnoreCase(host) == 0) {
                try {
                    return aeCache.findApplicationEntity(entry.getAeTitle());
                } catch (ConfigurationException e) {
                    return getFallBack();
                }
            }
        }
        return getFallBack();
    }

    private ApplicationEntity getFallBack() {
        String fallBackAETitle = arcDevExt.getHostNameAEFallBackEntry()
                .getAeTitle();
        try {
            return aeCache.get(fallBackAETitle);
        } catch (ConfigurationException e) {
            return null;
        }
    }
}