{{/* Chart name, overridable. */}}
{{- define "opsmind.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/* Release-qualified fullname. */}}
{{- define "opsmind.fullname" -}}
{{- if .Values.fullnameOverride -}}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s-%s" .Release.Name (include "opsmind.name" .) | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}

{{/* Common labels on every object. */}}
{{- define "opsmind.labels" -}}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: opsmind
{{- end -}}

{{/* Per-service selector labels. Call as (dict "root" $ "svc" $name). */}}
{{- define "opsmind.selectorLabels" -}}
app.kubernetes.io/name: {{ .svc }}
app.kubernetes.io/instance: {{ .root.Release.Name }}
{{- end -}}

{{/* The full image reference for a service value. */}}
{{- define "opsmind.image" -}}
{{- $img := index . 0 -}}{{- $root := index . 1 -}}
{{- printf "%s/%s:%s" $root.Values.image.registry $img $root.Values.image.tag -}}
{{- end -}}

{{/* Health-probe path by runtime. */}}
{{- define "opsmind.healthPath" -}}
{{- if eq . "java" -}}/actuator/health{{- else if eq . "static" -}}/{{- else -}}/health{{- end -}}
{{- end -}}
