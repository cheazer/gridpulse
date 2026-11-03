{{- define "dev.labels" -}}
app.kubernetes.io/name: {{ .component }}
app.kubernetes.io/instance: {{ .root.Release.Name }}
app.kubernetes.io/part-of: gridpulse-dev
app.kubernetes.io/managed-by: {{ .root.Release.Service }}
{{- end -}}

{{- define "dev.selectorLabels" -}}
app.kubernetes.io/name: {{ .component }}
app.kubernetes.io/instance: {{ .root.Release.Name }}
{{- end -}}

{{- define "dev.image" -}}
{{- $tag := required "set image.tag: the GridPulse images (MLflow, kafka-init) come from one build" .root.Values.image.tag -}}
{{- printf "%s%s:%s" .root.Values.image.registry .repository $tag -}}
{{- end -}}

{{/*
The "restricted" Pod Security Standard, as in the GridPulse chart. fsGroup makes mounted volumes writable by the
image's own user, so nothing has to start as root to chown its data directory.
*/}}
{{- define "dev.podSecurity" -}}
automountServiceAccountToken: false
securityContext:
  runAsNonRoot: true
  runAsUser: {{ .user }}
  runAsGroup: {{ .group | default .user }}
  fsGroup: {{ .group | default .user }}
  seccompProfile:
    type: RuntimeDefault
{{- end -}}

{{- define "dev.containerSecurity" -}}
securityContext:
  allowPrivilegeEscalation: false
  readOnlyRootFilesystem: true
  capabilities:
    drop: ["ALL"]
{{- end -}}

{{/* A config file passed with --set-file; installing without it fails with the flag to use. */}}
{{- define "dev.requiredFile" -}}
{{- required (printf "pass %s with --set-file (deploy/kind/up.sh does)" .name) .value -}}
{{- end -}}
