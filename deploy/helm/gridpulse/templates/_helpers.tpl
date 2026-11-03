{{/* Resource name for a component: "<release>-<component>", e.g. gridpulse-scheduler-api. */}}
{{- define "gridpulse.name" -}}
{{- printf "%s-%s" .root.Release.Name .component | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/* Labels that select a component's pods. Never change these on an existing release: selectors are immutable. */}}
{{- define "gridpulse.selectorLabels" -}}
app.kubernetes.io/name: {{ .component }}
app.kubernetes.io/instance: {{ .root.Release.Name }}
{{- end -}}

{{- define "gridpulse.labels" -}}
{{ include "gridpulse.selectorLabels" . }}
app.kubernetes.io/part-of: gridpulse
app.kubernetes.io/version: {{ .root.Values.image.tag | quote }}
app.kubernetes.io/managed-by: {{ .root.Release.Service }}
helm.sh/chart: {{ printf "%s-%s" .root.Chart.Name .root.Chart.Version }}
{{- end -}}

{{/* "<registry><repository>:<tag>"; the tag is required so every image in a release comes from one build. */}}
{{- define "gridpulse.image" -}}
{{- $tag := required "set image.tag: every GridPulse image in a release uses the same tag" .root.Values.image.tag -}}
{{- printf "%s%s:%s" .root.Values.image.registry .repository $tag -}}
{{- end -}}

{{/*
Pod settings that meet the "restricted" Pod Security Standard: a numeric non-root user, the runtime's default
seccomp profile, and no service-account token (no GridPulse service calls the Kubernetes API).
*/}}
{{- define "gridpulse.podSecurity" -}}
automountServiceAccountToken: false
securityContext:
  runAsNonRoot: true
  runAsUser: {{ .user }}
  runAsGroup: {{ .user }}
  seccompProfile:
    type: RuntimeDefault
{{- end -}}

{{/* Container settings for "restricted", plus a read-only root filesystem: only /tmp (an emptyDir) is writable. */}}
{{- define "gridpulse.containerSecurity" -}}
securityContext:
  allowPrivilegeEscalation: false
  readOnlyRootFilesystem: true
  capabilities:
    drop: ["ALL"]
{{- end -}}

{{- define "gridpulse.tmpVolumeMount" -}}
volumeMounts:
  - name: tmp
    mountPath: /tmp
{{- end -}}

{{- define "gridpulse.tmpVolume" -}}
volumes:
  - name: tmp
    emptyDir:
      sizeLimit: 256Mi
{{- end -}}

{{/*
OpenTelemetry settings, the same contract as in Compose (ADR-0014). Pod and namespace become resource attributes,
so a trace or metric says which pod sent it; Kubernetes expands $(VAR) from the env vars defined before it.
*/}}
{{- define "gridpulse.telemetryEnv" -}}
- name: K8S_POD_NAME
  valueFrom:
    fieldRef:
      fieldPath: metadata.name
- name: K8S_NAMESPACE
  valueFrom:
    fieldRef:
      fieldPath: metadata.namespace
{{- with .Values.telemetry.otlpEndpoint }}
- name: OTEL_EXPORTER_OTLP_ENDPOINT
  value: {{ . | quote }}
- name: OTEL_EXPORTER_OTLP_PROTOCOL
  value: http/protobuf
{{- end }}
- name: OTEL_RESOURCE_ATTRIBUTES
  value: "deployment.environment.name={{ .Values.telemetry.environment }},k8s.namespace.name=$(K8S_NAMESPACE),k8s.pod.name=$(K8S_POD_NAME)"
{{- end -}}

{{/* Where the database is; each workload adds its own login's password. */}}
{{- define "gridpulse.databaseEnv" -}}
- name: POSTGRES_HOST
  value: {{ .Values.database.host | quote }}
- name: TIMESCALE_PORT
  value: {{ .Values.database.port | quote }}
- name: POSTGRES_DB
  value: {{ .Values.database.name | quote }}
{{- end -}}

{{/* One key of the Secret create-secrets.sh makes, as an env var of the same name. */}}
{{- define "gridpulse.secretEnv" -}}
- name: {{ .name }}
  valueFrom:
    secretKeyRef:
      name: {{ .root.Values.secretName }}
      key: {{ .key | default .name }}
{{- end -}}

{{/*
Spring Boot's Actuator probes (step 4.3a). The startup probe gives the JVM and first polls up to 3 minutes. Every probe
waits 3s, not Kubernetes' default 1s: a /ready that checks the database on a busy node can take longer, and a
cancelled check counts as a failure.
*/}}
{{- define "gridpulse.springProbes" -}}
startupProbe:
  httpGet: { path: /actuator/health/liveness, port: http }
  timeoutSeconds: 3
  periodSeconds: 5
  failureThreshold: 36
livenessProbe:
  httpGet: { path: /actuator/health/liveness, port: http }
  timeoutSeconds: 3
  periodSeconds: 10
  failureThreshold: 3
readinessProbe:
  httpGet: { path: /actuator/health/readiness, port: http }
  timeoutSeconds: 3
  periodSeconds: 10
{{- end -}}

{{/* /health: the process is up (restart if not). /ready: it can serve, dependencies included (no traffic if not). */}}
{{- define "gridpulse.httpProbes" -}}
startupProbe:
  httpGet: { path: /health, port: http }
  timeoutSeconds: 3
  periodSeconds: 2
  failureThreshold: 30
livenessProbe:
  httpGet: { path: /health, port: http }
  timeoutSeconds: 3
  periodSeconds: 10
  failureThreshold: 3
readinessProbe:
  httpGet: { path: {{ .ready | default "/ready" }}, port: http }
  timeoutSeconds: 3
  periodSeconds: 5
{{- end -}}
