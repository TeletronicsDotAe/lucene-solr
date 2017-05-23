/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.solr.update;

/**
 * UpdateSemanticsMode is used to control details on how updates work semantically in Solr. Find more information under "Update Semantics"
 * on Solr Wiki
 */
public enum UpdateSemanticsMode {
	CLASSIC("classic") {
		@Override
		public RuleAndReason requireVersionFieldInSchema(RequestCommand cmd) {
			return notRequired;
		}
		
		@Override
		public RuleAndReason requireUniqueKeyFieldInSchema() {
			return notRequired;
		}
		
		@Override
		public RuleAndReason requireUniqueKeyFieldInSchemaAndDoc(RequestCommand cmd) {
		  if (cmd.getType() == RequestCommandType.ADD) return (cmd.isClassicOverwrite())?uniqueKeyFieldRequiredForClassicOverwrite:notRequired;
		  else if (cmd.getType() == RequestCommandType.DELETE) return uniqueKeyFieldRequiredForConcistencySchemaAndDoc;
		  return null;
		}
		
    @Override
		public RuleAndReason requireUpdateLog() {
			return notRequired;
		}
		
		@Override
		public RuleAndReason requireUpdateLog(RequestCommand cmd) {
			return notRequired;
		}
		
		@Override
		public boolean isClassicUpdate(RequestCommand cmd) {
			return true;
		}

		@Override
		public RuleAndReason requireExistingDocument(RequestCommand cmd) {
			return notRequired;
		}

    @Override
		public RuleAndReason requireNoExistingDocument(RequestCommand cmd) {
			return notRequired;
		} 
	},
	CONSISTENCY("consistency") {
		@Override
		public RuleAndReason requireVersionFieldInSchema(RequestCommand cmd) {
			return (cmd.getRequestVersion() > 0)?versionRequiredForConsistencyUpdate:notRequired;
		}
		
		@Override
		public RuleAndReason requireUniqueKeyFieldInSchema() {
			return uniqueKeyFieldRequiredForConcistencySchema;
		}
		
		@Override
		public RuleAndReason requireUniqueKeyFieldInSchemaAndDoc(RequestCommand cmd) {
			return uniqueKeyFieldRequiredForConcistencySchemaAndDoc;
		}

    @Override
		public RuleAndReason requireUpdateLog() {
			return updateLogRequiredForConcistency;
		}
		
		@Override
		public RuleAndReason requireUpdateLog(RequestCommand cmd) {
			return updateLogRequiredForConcistency;
		}
		
    @Override
		public boolean isClassicUpdate(RequestCommand cmd) {
			return false;
		}

		@Override
		public RuleAndReason requireExistingDocument(RequestCommand cmd) {
			return (cmd.getRequestVersion() > 0)?existingDocRquired:notRequired;
		}

    @Override
		public RuleAndReason requireNoExistingDocument(RequestCommand cmd) {
			return (cmd.getRequestVersion() <= 0)?nonExistingDocRequired:notRequired;
		}
	},
	CLASSIC_CONSISTENCY_HYBRID("classic-consistency-hybrid") {
		@Override
		public RuleAndReason requireVersionFieldInSchema(RequestCommand cmd) {
			return (cmd.getRequestVersion() > 0)?versionRequiredForConsistencyUpdate:notRequired;
		}
		
    @Override
		public RuleAndReason requireUniqueKeyFieldInSchema() {
			return notRequired;
		}
		
		@Override
		public RuleAndReason requireUniqueKeyFieldInSchemaAndDoc(RequestCommand cmd) {
		  if (cmd.getType() == RequestCommandType.ADD) return (cmd.getRequestVersion() != 0)?uniqueKeyFieldRequiredForConcistencySchemaAndDoc:((cmd.isClassicOverwrite())?uniqueKeyFieldRequiredForClassicOverwrite:notRequired);
		  else if (cmd.getType() == RequestCommandType.DELETE) return uniqueKeyFieldRequiredForConcistencySchemaAndDoc;
		  return null;
		}

    @Override
		public RuleAndReason requireUpdateLog() {
			return notRequired;
		}
		
		@Override
		public RuleAndReason requireUpdateLog(RequestCommand cmd) {
			return (cmd.getRequestVersion() != 0)?updateLogRequiredForConcistency:notRequired;
		}
		
    @Override
		public boolean isClassicUpdate(RequestCommand cmd) {
			return (cmd.getRequestVersion() == 0);
		}

		@Override
		public RuleAndReason requireExistingDocument(RequestCommand cmd) {
			return (cmd.getRequestVersion() > 0)?existingDocRquired:notRequired;
		}

    @Override
		public RuleAndReason requireNoExistingDocument(RequestCommand cmd) {
			return (cmd.getRequestVersion() < 0)?nonExistingDocRequiredHybrid:notRequired;
		}
	};
	
	private String text;

	private UpdateSemanticsMode(String text) {
		this.text = text;
	}

	@Override
	public String toString() {
		return this.text;
	}

	public static UpdateSemanticsMode getDefault() {
		return CLASSIC_CONSISTENCY_HYBRID;
	}

	public static UpdateSemanticsMode fromString(String text) {
		if (text != null) {
			for (UpdateSemanticsMode usm : UpdateSemanticsMode.values()) {
				if (text.equalsIgnoreCase(usm.text)) {
					return usm;
				}
			}
		}
		return null;
	}
	
	public static class RuleAndReason {
		public boolean ruleEnforced;
		public String reason;
	}
	
	public static enum RequestCommandType {
	  ADD,
	  DELETE
	}
	public static interface RequestCommand {
	  boolean isClassicOverwrite();
	  long getRequestVersion();
	  RequestCommandType getType();
	}
	
	private static final RuleAndReason notRequired;
	private static final RuleAndReason versionRequiredForConsistencyUpdate;
	private static final RuleAndReason uniqueKeyFieldRequiredForClassicOverwrite;
	private static final RuleAndReason uniqueKeyFieldRequiredForConcistencySchema;
	private static final RuleAndReason uniqueKeyFieldRequiredForConcistencySchemaAndDoc;
	private static final RuleAndReason updateLogRequiredForConcistency;
	private static final RuleAndReason existingDocRquired;
	private static final RuleAndReason nonExistingDocRequired;
	private static final RuleAndReason nonExistingDocRequiredHybrid;
	static {
		String VERSION_FIELD = "_version_"; //Cannot depend on VersionInfo.VERSION_FIELD :-/

		notRequired = new RuleAndReason();
		notRequired.ruleEnforced = false;
		notRequired.reason = "Not required";
		versionRequiredForConsistencyUpdate = new RuleAndReason();
		versionRequiredForConsistencyUpdate.ruleEnforced = true;
		versionRequiredForConsistencyUpdate.reason = VERSION_FIELD + " field required in schema in order to do consistent document updates (" + VERSION_FIELD + " > 0 specified explicitly in document)";
		uniqueKeyFieldRequiredForClassicOverwrite = new RuleAndReason();
		uniqueKeyFieldRequiredForClassicOverwrite.ruleEnforced = true;
		uniqueKeyFieldRequiredForClassicOverwrite.reason = "Unique key field required in schema and document when using overwrite feature";
		uniqueKeyFieldRequiredForConcistencySchema = new RuleAndReason();
		uniqueKeyFieldRequiredForConcistencySchema.ruleEnforced = true;
		uniqueKeyFieldRequiredForConcistencySchema.reason = "Unique key field required in schema";
		uniqueKeyFieldRequiredForConcistencySchemaAndDoc = new RuleAndReason();
		uniqueKeyFieldRequiredForConcistencySchemaAndDoc.ruleEnforced = true;
		uniqueKeyFieldRequiredForConcistencySchemaAndDoc.reason = "Unique key field required in schema and document";
		updateLogRequiredForConcistency = new RuleAndReason();
		updateLogRequiredForConcistency.ruleEnforced = true;
		updateLogRequiredForConcistency.reason = "Update-log required in order to do consistent document inserts and/or updates";
		existingDocRquired = new RuleAndReason();
		existingDocRquired.ruleEnforced = true;
		existingDocRquired.reason = "Attempt to update (" + VERSION_FIELD + " > 0 specified explicitly in document) document failed. Document does not exist";
		nonExistingDocRequired = new RuleAndReason();
		nonExistingDocRequired.ruleEnforced = true;
		nonExistingDocRequired.reason = "Attempt to insert (" + VERSION_FIELD + " <= 0 specified explicitly in document) document failed. Document already exists";
		nonExistingDocRequiredHybrid = new RuleAndReason();
		nonExistingDocRequiredHybrid.ruleEnforced = true;
		nonExistingDocRequiredHybrid.reason = "Attempt to insert (" + VERSION_FIELD + " <= 0 specified explicitly in document) document failed. Document already exists";
	}

	public abstract RuleAndReason requireVersionFieldInSchema(RequestCommand cmd);
	public abstract RuleAndReason requireUniqueKeyFieldInSchema();
	public abstract RuleAndReason requireUniqueKeyFieldInSchemaAndDoc(RequestCommand cmd);
	public abstract RuleAndReason requireUpdateLog();
	public abstract RuleAndReason requireUpdateLog(RequestCommand cmd);
	public abstract boolean isClassicUpdate(RequestCommand cmd);
	public abstract RuleAndReason requireExistingDocument(RequestCommand cmd);
	public abstract RuleAndReason requireNoExistingDocument(RequestCommand cmd);
	
	public boolean requireVersionCheck(RequestCommand cmd) {
		return requireVersionFieldInSchema(cmd).ruleEnforced;
	}
	
	public boolean needToGetAndCheckAgainstExistingDocument(RequestCommand cmd) {
		return requireExistingDocument(cmd).ruleEnforced || requireNoExistingDocument(cmd).ruleEnforced || requireVersionCheck(cmd);
	}
	
  public boolean needToDeleteOldVersionOfDocument(RequestCommand cmd) {
		return !((isClassicUpdate(cmd) && !cmd.isClassicOverwrite()) || requireNoExistingDocument(cmd).ruleEnforced);
	}

}
