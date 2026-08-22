{{/*
Standard labels per CLAUDE.md. Call as:
  {{ include "toy-rental.labels" (dict "name" "api-gateway" "root" $) | nindent 4 }}
*/}}
{{- define "toy-rental.labels" -}}
app.kubernetes.io/name: {{ .name }}
app.kubernetes.io/version: {{ .root.Chart.AppVersion | quote }}
app.kubernetes.io/part-of: toy-rental
app.kubernetes.io/managed-by: helm
{{- end -}}
